package com.example.personeltracking2026.ui.personel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.personeltracking2026.App
import com.example.personeltracking2026.core.session.SessionManager
import com.example.personeltracking2026.data.model.LocationData
import com.example.personeltracking2026.data.model.PersonelData
import com.example.personeltracking2026.data.model.getClassification
import com.example.personeltracking2026.data.repository.LocationRepository
import com.example.personeltracking2026.data.repository.PersonelRepository
import com.example.personeltracking2026.data.repository.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter

data class LocationState(
    val data       : LocationData? = null,
    val gpsStrength: Int           = 0,
    val isInZone   : Boolean       = false,
    val error      : String?       = null
)

data class BatteryState(
    val percent   : Int     = 0,
    val isCharging: Boolean = false
)

data class HeartRateState(
    val bpm        : Int     = 0,
    val timestamp  : Long    = 0L,
    val isConnected: Boolean = false,
    val deviceName : String  = ""
)

class PersonelViewModel(
    application                   : Application,
    private val repository        : PersonelRepository,
    private val locationRepository: LocationRepository,
    private val sessionManager    : SessionManager
) : AndroidViewModel(application) {

    // LOG CSV (dipakai kalau ingin debug GPS ke file)
    private val rawFile by lazy {
        File(
            getApplication<Application>().getExternalFilesDir(null),
            "gps_filtered.csv"
        ).apply {
            if (!exists()) { createNewFile(); writeText("timestamp,lat,lon,accuracy\n") }
        }
    }

    fun saveFilteredCsv(loc: LocationData) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                FileWriter(rawFile, true).use { it.append("${loc.timestamp},${loc.lat},${loc.lon},${loc.accuracy}\n") }
            } catch (e: Exception) { Log.e("CSV_FILTERED", "Error", e) }
        }
    }

    companion object {
        private const val ZONE_CENTER_LAT    = -7.868729
        private const val ZONE_CENTER_LON    = 105.643117
        private const val ZONE_RADIUS_METERS = 500.0
    }

    private var locationJob  : Job? = null
    private var lastLocation : LocationData? = null
    private var lastAccepted : LocationData? = null
    private val smoothWindow = ArrayDeque<LocationData>()

    // ─── STATE FLOWS ─────────────────────────────────────────────────────────

    private val _personelState  = MutableStateFlow<PersonelState>(PersonelState.Loading)
    val personelState : StateFlow<PersonelState> = _personelState

    private val _locationState  = MutableStateFlow(LocationState())
    val locationState : StateFlow<LocationState> = _locationState.asStateFlow()

    private val _batteryState   = MutableStateFlow(BatteryState())
    val batteryState  : StateFlow<BatteryState> = _batteryState.asStateFlow()

    private val _mqttConnected  = MutableStateFlow(false)
    val mqttConnected : StateFlow<Boolean> = _mqttConnected.asStateFlow()

    private val _lastSyncTime   = MutableStateFlow(0L)
    val lastSyncTime  : StateFlow<Long> = _lastSyncTime.asStateFlow()

    private val _heartRateState = MutableStateFlow(HeartRateState())
    val heartRateState: StateFlow<HeartRateState> = _heartRateState.asStateFlow()

    // ─── MQTT ────────────────────────────────────────────────────────────────

    // Expose mqttManager untuk reconnect di Activity
    val mqttManager = (application as App).mqttManager.apply {
        onConnected      = { _mqttConnected.value = true }
        onDisconnected   = { _mqttConnected.value = false }
        onPublishSuccess = {
            val now = System.currentTimeMillis()
            Log.d("MQTT_TIMER", "SUCCESS publish at $now")
            _lastSyncTime.value = now
        }
    }

    // ─── BATTERY RECEIVER ────────────────────────────────────────────────────

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            viewModelScope.launch(Dispatchers.IO) { refreshBattery() }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { refreshBattery() }
    }

    fun registerBatteryReceiver(context: Context) {
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun unregisterBatteryReceiver(context: Context) {
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
    }

    // ─── PERSONEL ────────────────────────────────────────────────────────────

    fun loadPersonelDetail(userId: Int, token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _personelState.value = PersonelState.Loading }

            when (val result = repository.getPersonelDetail(userId, token)) {
                is Result.Success -> {
                    val data = result.data
                    Log.d("DETAIL_DATA", "data = $data")

                    val safeName   = data.full_name ?: data.name ?: sessionManager.getName()
                    val safeAvatar = resolveCmsImageUrl(data.avatar_url ?: data.image)
                        ?: sessionManager.getAvatarUrl().takeIf { it.isNotBlank() }

                    sessionManager.saveName(safeName)

                    val satuan   = data.getClassification("Satuan").ifBlank { data.satuan?.name ?: "" }.ifBlank { sessionManager.getSatuan() }
                    val rank     = data.getClassification("Rank").ifBlank { data.rank?.name ?: "" }.ifBlank { sessionManager.getRank() }
                    val unit     = data.getClassification("Unit").ifBlank { data.unit?.name ?: "" }.ifBlank { sessionManager.getUnit() }
                    val regu     = data.getClassification("Regu").ifBlank { data.regu?.name ?: "" }.ifBlank { sessionManager.getRegu() }
                    val batalyon = data.getClassification("Batalyon").ifBlank { data.batalyon?.name ?: "" }.ifBlank { sessionManager.getBatalyon() }
                    val peleton  = data.getClassification("Peleton").ifBlank { data.peleton?.name ?: "" }.ifBlank { sessionManager.getPeleton() }
                    val kompi    = data.getClassification("Kompi").ifBlank { data.kompi?.name ?: "" }.ifBlank { sessionManager.getKompi() }
                    val divisi   = data.getClassification("Divisi").ifBlank { data.divisi?.name ?: "" }.ifBlank { sessionManager.getDivisi() }
                    val brigade  = data.getClassification("Brigade").ifBlank { data.brigade?.name ?: "" }.ifBlank { sessionManager.getBrigade() }
                    val team     = data.getClassification("Team").ifBlank { data.team?.name ?: "" }.ifBlank { sessionManager.getTeam() }
                    val nrp      = data.nrp?.takeIf { it.isNotBlank() } ?: sessionManager.getNrp()

                    sessionManager.savePersonelDetail(
                        id        = sessionManager.getUserId()?.toString() ?: "",
                        nrp       = nrp,
                        name      = sessionManager.getName(),
                        satuan    = satuan,
                        batalyon  = batalyon,
                        peleton   = peleton,
                        regu      = regu,
                        kompi     = kompi,
                        divisi    = divisi,
                        brigade   = brigade,
                        team      = team,
                        unit      = unit,
                        rank      = rank,
                        avatarUrl = safeAvatar
                    )

                    withContext(Dispatchers.Main) { _personelState.value = PersonelState.Success(data) }
                }
                is Result.Error -> withContext(Dispatchers.Main) {
                    _personelState.value = PersonelState.Error(result.message)
                }
                else -> {}
            }
        }
    }

    private fun resolveCmsImageUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http://") || path.startsWith("https://")) path
        else "https://cms.aturwalpat.com/images/${path.trimStart('/')}"
    }

    // ─── LOCATION (UI only — MQTT publish dilakukan oleh MqttLocationService) ─

    fun startLocationUpdates(intervalMs: Long = 5000L) {
        locationJob?.cancel()

        locationJob = viewModelScope.launch {
            // Collect dari SharedFlow repository yang diisi oleh MqttLocationService
            // Tidak membuat AppLocationManager sendiri
            locationRepository.locationFlow.collect { kotlinResult ->
                val locationData = kotlinResult.getOrNull()
                val error        = kotlinResult.exceptionOrNull()

                if (locationData != null) {
                    val filteredLoc = processLocation(locationData) ?: return@collect

                    _locationState.value = LocationState(
                        data        = filteredLoc,
                        gpsStrength = accuracyToStrength(filteredLoc.accuracy),
                        isInZone    = checkInZone(filteredLoc.lat, filteredLoc.lon)
                    )

                    val app = getApplication<Application>() as App
                    app.currentLat      = filteredLoc.lat
                    app.currentLon      = filteredLoc.lon
                    app.currentAccuracy = filteredLoc.accuracy

                    lastLocation = filteredLoc

                } else if (error != null) {
                    _locationState.update { it.copy(error = error.message, gpsStrength = 0) }
                }
            }
        }
    }

    // ─── SOS ─────────────────────────────────────────────────────────────────

    fun publishSos(sosValue: Int) {
        val loc = _locationState.value.data ?: return

        val app          = getApplication<Application>() as App
        val serial       = app.mqttManager.let {
            com.example.personeltracking2026.utils.DeviceIdentityManager(getApplication()).getIdentity()
        } ?: return

        val payload = com.example.personeltracking2026.core.mqtt.MqttPayloadBuilder.buildRadioSosPayload(
            session      = sessionManager,
            serialNumber = serial.serial,
            androidId    = serial.androidId,
            lat          = loc.lat,
            lon          = loc.lon,
            acc          = loc.accuracy,
            sos          = sosValue
        )
        mqttManager.publishRadioSos(payload)
    }

    // ─── HEART RATE (BLE) ────────────────────────────────────────────────────

    fun updateHeartRate(bpm: Int, deviceName: String = "") {
        _heartRateState.update {
            it.copy(
                bpm        = bpm,
                timestamp  = System.currentTimeMillis(),
                deviceName = deviceName.ifEmpty { it.deviceName }
            )
        }
        val app = getApplication<Application>()
        if (app is App) {
            app.currentHeartRate   = bpm
            app.currentHeartRateTs = System.currentTimeMillis()
        }
        Log.d("HR_DEBUG", "UPDATE HR = $bpm")
    }

    fun onBleConnected(deviceName: String) {
        _heartRateState.update { it.copy(isConnected = true, deviceName = deviceName) }
    }

    fun onBleDisconnected() {
        _heartRateState.update { it.copy(isConnected = false) }
    }

    // ─── BATTERY ─────────────────────────────────────────────────────────────

    suspend fun refreshBattery() = withContext(Dispatchers.IO) {
        val bm = getApplication<Application>().getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percent    = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = bm.isCharging
        if (percent > 0) {
            withContext(Dispatchers.Main) {
                _batteryState.value = BatteryState(percent, isCharging)
            }
        }
    }

    fun onMqttPublishSuccess() {
        _lastSyncTime.value = System.currentTimeMillis()
    }

    // ─── HELPERS ─────────────────────────────────────────────────────────────

    private fun accuracyToStrength(accuracy: Float) = when {
        accuracy <= 10f  -> 95
        accuracy <= 20f  -> 80
        accuracy <= 50f  -> 60
        accuracy <= 100f -> 40
        else             -> 20
    }

    private fun checkInZone(lat: Double, lon: Double): Boolean {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat, lon, ZONE_CENTER_LAT, ZONE_CENTER_LON, results)
        return results[0] <= ZONE_RADIUS_METERS
    }

    private fun distance(a: LocationData, b: LocationData): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, results)
        return results[0]
    }

    private fun isMoving(newLoc: LocationData, last: LocationData?): Boolean {
        if (last == null) return true
        val dist     = distance(last, newLoc)
        val dtMillis = newLoc.timestamp - last.timestamp
        if (dtMillis <= 0) return false
        val timeSec  = dtMillis / 1000f
        if (timeSec > 10f) return dist > 5f
        val speed    = dist / timeSec
        return speed > 1.5f || dist > 8f
    }

    private fun smooth(loc: LocationData, dt: Float): LocationData {
        val maxWindow = (5f / dt).toInt().coerceIn(5, 15)
        smoothWindow.addLast(loc)
        if (smoothWindow.size > maxWindow) smoothWindow.removeFirst()
        val avgLat = smoothWindow.map { it.lat }.average()
        val avgLon = smoothWindow.map { it.lon }.average()
        return loc.copy(lat = avgLat, lon = avgLon)
    }

    private fun processLocation(newLoc: LocationData): LocationData? {
        if (newLoc.accuracy > 20f) return null

        val last = lastAccepted
        if (last == null) {
            lastAccepted = newLoc
            return newLoc
        }

        val dt     = ((newLoc.timestamp - last.timestamp) / 1000f).coerceAtLeast(0.5f)
        val dist   = distance(last, newLoc)
        val moving = isMoving(newLoc, last)
        if (moving) smoothWindow.clear()

        val maxJump = 8f + 2f * dt
        if (dist > maxJump) {
            Log.d("FILTER", "OUTLIER: $dist")
            return null
        }

        val smallMove = 3f + dt
        if (!moving && dist < smallMove) {
            val tau        = 6f
            val alpha      = dt / (tau + dt)
            val blendedLat = last.lat + alpha * (newLoc.lat - last.lat)
            val blendedLon = last.lon + alpha * (newLoc.lon - last.lon)
            val blended    = newLoc.copy(lat = blendedLat, lon = blendedLon)
            val finalLoc   = smooth(blended, dt)
            lastAccepted   = finalLoc
            return finalLoc
        }

        val alpha = if (moving) {
            when {
                newLoc.accuracy < 5  -> 0.7f
                newLoc.accuracy < 10 -> 0.5f
                else                 -> 0.3f
            }
        } else {
            dt / (5f + dt)
        }

        val blendedLat = last.lat + alpha * (newLoc.lat - last.lat)
        val blendedLon = last.lon + alpha * (newLoc.lon - last.lon)
        val blended    = newLoc.copy(lat = blendedLat, lon = blendedLon)
        val finalLoc   = if (!moving) smooth(blended, dt) else blended
        lastAccepted   = finalLoc
        return finalLoc
    }

    // ─── FACTORY ─────────────────────────────────────────────────────────────

    class Factory(
        private val application       : Application,
        private val repository        : PersonelRepository,
        private val locationRepository: LocationRepository,
        private val sessionManager    : SessionManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PersonelViewModel(application, repository, locationRepository, sessionManager) as T
    }
}

sealed class PersonelState {
    object Loading                        : PersonelState()
    data class Success(val data: PersonelData) : PersonelState()
    data class Error(val message: String) : PersonelState()
}