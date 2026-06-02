package com.example.personeltracking2026.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.personeltracking2026.App
import com.example.personeltracking2026.BuildConfig
import com.example.personeltracking2026.R
import com.example.personeltracking2026.core.device.DeviceMode
import com.example.personeltracking2026.core.location.AppLocationManager
import com.example.personeltracking2026.core.mqtt.MqttPayloadBuilder
import com.example.personeltracking2026.core.mqtt.MqttReconnectManager
import com.example.personeltracking2026.core.session.SessionManager
import com.example.personeltracking2026.core.utils.Constants
import com.example.personeltracking2026.data.model.LocationData
import com.example.personeltracking2026.utils.DeviceIdentityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MqttLocationService : Service() {

    companion object {
        private const val TAG          = "MqttLocationService"
        const val CHANNEL_ID           = "mqtt_location_channel"
        const val NOTIFICATION_ID      = 1001
        private const val ACTION_START = "ACTION_START"
        private const val ACTION_STOP  = "ACTION_STOP"

        fun startService(context: Context) {
            val intent = Intent(context, MqttLocationService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MqttLocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var sessionManager    : SessionManager
    private lateinit var appLocationManager: AppLocationManager
    private lateinit var reconnectManager  : MqttReconnectManager
    private lateinit var wakeLock          : PowerManager.WakeLock

    // Cache identity — dibuat sekali di onCreate, bukan tiap publish
    private var cachedSerial   : String? = null
    private var cachedAndroidId: String? = null

    // Throttle
    private var lastPublishTime  = 0L
    private var publishIntervalMs = 5000L

    private val intervalChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Constants.ACTION_INTERVAL_CHANGED) return
            val intervalText = intent.getStringExtra(Constants.EXTRA_INTERVAL_TEXT)
                ?: Constants.DEFAULT_INTERVAL_TEXT
            Log.d(TAG, "Interval changed: $intervalText")
            applyNewInterval(intervalText)
        }
    }

    // ─────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        sessionManager       = SessionManager(this)
        appLocationManager   = AppLocationManager(this)
        reconnectManager     = MqttReconnectManager(this, (application as App).mqttManager)

        // Cache identity sekali saja
        val identity = DeviceIdentityManager(this).getIdentity()
        cachedSerial    = identity?.serial
        cachedAndroidId = identity?.androidId

        // WakeLock
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PersonelTracking::MqttWakeLock"
        ).apply { acquire(60 * 60 * 1000L) }

        createNotificationChannel()

        ContextCompat.registerReceiver(
            this,
            intervalChangedReceiver,
            IntentFilter(Constants.ACTION_INTERVAL_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Service starting")
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification(),
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        } else {
                            0
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "startForeground failed: ${e.message}")
                    stopSelf()
                    return START_NOT_STICKY
                }

                reconnectManager.start()
                startLocationUpdates()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Service stopping")
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectManager.stop()
        appLocationManager.stopUpdates()
        serviceScope.cancel()
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        try { unregisterReceiver(intervalChangedReceiver) } catch (_: Exception) {}
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────
    //  Location
    // ─────────────────────────────────────────────

    private fun startLocationUpdates() {
        val prefs = getSharedPreferences("mqtt_settings", Context.MODE_PRIVATE)
        val intervalStr = prefs.getString("interval", Constants.DEFAULT_INTERVAL_TEXT)
            ?: Constants.DEFAULT_INTERVAL_TEXT
        publishIntervalMs = parseIntervalToMs(intervalStr)

        appLocationManager.setInterval(publishIntervalMs)
        lastPublishTime = 0L

        val app = application as App

        appLocationManager.onLocationUpdate = { lat, lon, accuracy, source ->
            val now = System.currentTimeMillis()

            // Update global state di App (untuk SosManager dll)
            app.currentLat      = lat
            app.currentLon      = lon
            app.currentAccuracy = accuracy

            // Emit ke LocationRepository → UI bisa observe via ViewModel
            serviceScope.launch {
                app.locationRepository.emit(LocationData(lat, lon, accuracy, source))
            }

            // Publish MQTT dengan throttle
            if (now - lastPublishTime >= publishIntervalMs) {
                lastPublishTime = now
                Log.d(TAG, "Location [$source]: $lat, $lon → publishing")
                publishLocation(lat, lon, accuracy)
            } else {
                Log.d(TAG, "Location [$source]: throttled, skip")
            }
        }

        appLocationManager.onLocationError = { message ->
            Log.e(TAG, "Location error: $message")
            serviceScope.launch {
                app.locationRepository.emitError(message)
            }
        }

        appLocationManager.startUpdates()
        Log.d(TAG, "Location updates started — interval: ${publishIntervalMs / 1000}s")
    }

    private fun applyNewInterval(intervalText: String) {
        val newIntervalMs = parseIntervalToMs(intervalText)
        if (newIntervalMs == publishIntervalMs) {
            Log.d(TAG, "Interval unchanged, skip: $newIntervalMs ms")
            return
        }
        Log.d(TAG, "Applying new interval: $intervalText → $newIntervalMs ms")
        publishIntervalMs = newIntervalMs
        lastPublishTime   = 0L
        appLocationManager.stopUpdates()
        appLocationManager.setInterval(publishIntervalMs)
        appLocationManager.startUpdates()
    }

    // ─────────────────────────────────────────────
    //  Publish MQTT
    // ─────────────────────────────────────────────

    private fun publishLocation(lat: Double, lon: Double, accuracy: Float) {
        serviceScope.launch {
            val app = application as App

            if (
                app.currentMode != DeviceMode.RADIO &&
                app.currentMode != DeviceMode.RADIO_BODYCAM
            ) {
                Log.d(TAG, "Not RADIO/RADIO_BODYCAM mode → skip publish")
                return@launch
            }

            val mqttManager = app.mqttManager
            if (!mqttManager.isConnected()) {
                Log.d(TAG, "MQTT not connected, skip publish")
                return@launch
            }

            // Pakai cached identity — tidak buat object baru tiap publish
            val serial    = cachedSerial ?: run {
                Log.e(TAG, "Serial number missing")
                return@launch
            }
            val androidId = cachedAndroidId ?: run {
                Log.e(TAG, "Android ID missing")
                return@launch
            }

            val nowMs = System.currentTimeMillis()
            val hr    = app.currentHeartRate
            val hrTs  = app.currentHeartRateTs.takeIf { it > 0 } ?: nowMs

            val payload = MqttPayloadBuilder.buildRadioDataPayload(
                session      = sessionManager,
                serialNumber = serial,
                androidId    = androidId,
                lat          = lat,
                lon          = lon,
                acc          = accuracy,
                gpsTimestamp = nowMs,
                heartrate    = hr,
                heartrateTs  = hrTs,
                batteryLevel = getBatteryLevel(),
                appVersion   = BuildConfig.APP_VERSION,
                rtmpUrl      = StreamUtils.getRtmpUrl(serial)
            )

            mqttManager.publishRadioData(payload)
        }
    }

    private fun getBatteryLevel(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { 0 }
    }

    private fun parseIntervalToMs(interval: String): Long {
        return when {
            interval.contains("minute") -> {
                val m = interval.filter { it.isDigit() }.toLongOrNull() ?: 1L
                m * 60 * 1000
            }
            interval.contains("second") -> {
                val s = interval.filter { it.isDigit() }.toLongOrNull() ?: 10L
                s * 1000
            }
            else -> 10000L
        }
    }

    // ─────────────────────────────────────────────
    //  Notification
    // ─────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Personel Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tracking Location Personel Active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = packageManager
            .getLaunchIntentForPackage(packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Personel Tracking Active")
            .setContentText("Send Location Regulary")
            .setSmallIcon(R.drawable.ic_location_pin)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}