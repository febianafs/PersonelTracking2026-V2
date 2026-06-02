package com.example.personeltracking2026.core.mqtt

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.personeltracking2026.core.mqtt.queue.MqttQueueManager
import com.example.personeltracking2026.core.utils.MqttLogger
import com.example.personeltracking2026.data.model.BodycamDataPayload
import com.example.personeltracking2026.data.model.BodycamSosPayload
import com.example.personeltracking2026.data.model.RadioDataPayload
import com.example.personeltracking2026.data.model.RadioSosPayload
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.nio.charset.StandardCharsets

class MqttManager(private val context: Context) {

    lateinit var queueManager: MqttQueueManager

    init {
        queueManager = MqttQueueManager(context)
    }

    companion object {
        private const val TAG            = "MqttManager"
        const val QOS_DATA               = 1
        const val QOS_SOS                = 2
        private const val MAX_RETRY      = 5
        private const val RETRY_DELAY_MS = 5_000L
    }

    var onConnected      : (() -> Unit)?                                = null
    var onDisconnected   : (() -> Unit)?                                = null
    var onPublishSuccess : ((topic: String) -> Unit)?                   = null
    var onPublishFailed  : ((topic: String, reason: String) -> Unit)?   = null

    private val scope = CoroutineScope(Dispatchers.IO)

    // FIX 1: Baca topic dari MqttTopicManager, bukan konstanta hardcoded
    private fun topics() = MqttTopicManager(context).load()

    private var client             : Mqtt3AsyncClient? = null
    private var retryJob           : Job?              = null
    private var retryCount                             = 0
    private var isIntentionallyStopped                 = false

    // ─── PUBLIC ──────────────────────────────────────────────────────────────

    fun connect() {
        isIntentionallyStopped = false
        retryCount = 0
        scope.launch { doConnect() }
    }

    // FIX 3: Fungsi baru untuk test connection pakai config dari luar (input field)
    fun connectWithConfig(config: MqttConfig) {
        isIntentionallyStopped = false
        retryCount = 0
        scope.launch { doConnectWithConfig(config) }
    }

    fun disconnect() {
        isIntentionallyStopped = true
        retryJob?.cancel()
        scope.launch {
            try {
                client?.disconnect()
                MqttLogger.connect(null, false)
            } catch (e: Exception) {
                MqttLogger.error("Disconnect error: ${e.message}")
            }
        }
    }

    /**
     * Dipanggil setelah user Save settings baru di SettingsActivity.
     * Disconnect dari broker lama, reconnect dengan config terbaru.
     */
    fun reconnectWithNewSettings() {
        isIntentionallyStopped = true
        retryJob?.cancel()
        scope.launch {
            try { client?.disconnect() } catch (e: Exception) { /* ignore */ }
            delay(500)
            isIntentionallyStopped = false
            retryCount = 0
            doConnect()
        }
    }

    fun publishDirect(topic: String, payload: String) {
        client?.publishWith()
            ?.topic(topic)
            ?.payload(payload.toByteArray())
            ?.send()
        MqttLogger.publish(topic, payload)
    }

    fun isConnected(): Boolean = client?.state?.isConnected == true

    // FIX 1: Semua fungsi publish sekarang baca topic dari MqttTopicManager
    fun publishRadioData(payload: RadioDataPayload) {
        scope.launch {
            val json = buildRadioDataJson(payload)
            publish(topics().personelDataTopic, json, QOS_DATA)
        }
    }

    fun publishRadioSos(payload: RadioSosPayload) {
        scope.launch {
            val json = buildRadioSosJson(payload)
            publish(topics().personelSosTopic, json, QOS_SOS)
        }
    }

    fun publishBodycamData(payload: BodycamDataPayload) {
        scope.launch {
            val json = buildBodycamDataJson(payload)
            publish(topics().bodycamDataTopic, json, QOS_DATA)
        }
    }

    fun publishBodycamSos(payload: BodycamSosPayload) {
        scope.launch {
            val json = buildBodycamSosJson(payload)
            publish(topics().bodycamSosTopic, json, QOS_SOS)
        }
    }

    // ─── INTERNAL ────────────────────────────────────────────────────────────

    private fun doConnect() {
        val config = MqttConfigManager(context).load()
        val host   = config.host
        val port   = if (config.useWebSocket) config.wsPort else config.tcpPort
        val user   = config.username
        val pass   = config.password

        Log.d(
            "MQTT_CONFIG",
            """
        CONNECT USING:
        host=$host
        port=$port
        username=$user
        useWebSocket=${config.useWebSocket}
        """.trimIndent()
        )

        val clientId = "android_${Build.MODEL}_${System.currentTimeMillis()}"
            .replace(" ", "_")
            .take(23)

        val builder = Mqtt3Client.builder()
            .identifier(clientId)
            .serverHost(host)
            .serverPort(port)

        if (config.useWebSocket) {
            builder.webSocketConfig()
                .serverPath("/")
                .applyWebSocketConfig()
        }

        builder.simpleAuth()
            .username(user)
            .password(pass.toByteArray(StandardCharsets.UTF_8))
            .applySimpleAuth()

        client = builder.buildAsync()

        client?.connect()
            ?.whenComplete { connAck: Mqtt3ConnAck?, throwable: Throwable? ->
                if (throwable != null) {
                    MqttLogger.error("Connect failed: ${throwable.message}")
                    onDisconnected?.invoke()
                    if (!isIntentionallyStopped) scheduleRetry()
                    return@whenComplete
                }
                if (connAck?.returnCode == Mqtt3ConnAckReturnCode.SUCCESS) {
                    val protocol = if (config.useWebSocket) "ws" else "tcp"
                    MqttLogger.connect("$protocol://$host:$port", retryCount > 0)
                    retryCount = 0
                    onConnected?.invoke()
                    scope.launch {
                        queueManager.flush(this@MqttManager)
                    }
                } else {
                    MqttLogger.error("Connect rejected: ${connAck?.returnCode}")
                    onDisconnected?.invoke()
                    if (!isIntentionallyStopped) scheduleRetry()
                }
            }
    }

    // FIX 3: doConnectWithConfig — terima config dari parameter, bukan dari SharedPrefs
    // Dipakai khusus untuk Test Connection di SettingsActivity
    private fun doConnectWithConfig(config: MqttConfig) {
        val host = config.host
        val port = if (config.useWebSocket) config.wsPort else config.tcpPort
        val user = config.username
        val pass = config.password

        val clientId = "android_test_${System.currentTimeMillis()}".take(23)

        val builder = Mqtt3Client.builder()
            .identifier(clientId)
            .serverHost(host)
            .serverPort(port)

        if (config.useWebSocket) {
            builder.webSocketConfig()
                .serverPath("/")
                .applyWebSocketConfig()
        }

        builder.simpleAuth()
            .username(user)
            .password(pass.toByteArray(StandardCharsets.UTF_8))
            .applySimpleAuth()

        client = builder.buildAsync()

        client?.connect()?.whenComplete { connAck, throwable ->
            if (throwable != null) {
                MqttLogger.error("Test connect failed: ${throwable.message}")
                onDisconnected?.invoke()
                return@whenComplete
            }
            if (connAck?.returnCode == Mqtt3ConnAckReturnCode.SUCCESS) {
                MqttLogger.connect("test://$host:$port", false)
                onConnected?.invoke()
            } else {
                MqttLogger.error("Test connect rejected: ${connAck?.returnCode}")
                onDisconnected?.invoke()
            }
        }
    }

    private fun publish(topic: String, json: String, qos: Int) {
        if (!isConnected()) {
            scope.launch {
                queueManager.save(topic, json)
            }
            Log.d("MQTT_QUEUE", "Saved to queue DB")
            onPublishFailed?.invoke(topic, "Queued (offline)")
            return
        }
        val mqttQos = if (qos >= 2) MqttQos.EXACTLY_ONCE else MqttQos.AT_LEAST_ONCE

        client?.publishWith()
            ?.topic(topic)
            ?.qos(mqttQos)
            ?.payload(json.toByteArray(StandardCharsets.UTF_8))
            ?.retain(false)
            ?.send()
            ?.whenComplete { _, throwable ->
                if (throwable != null) {
                    MqttLogger.error("Publish failed ($topic): ${throwable.message}")
                    onPublishFailed?.invoke(topic, throwable.message ?: "Unknown error")
                } else {
                    MqttLogger.publish(topic, json)
                    onPublishSuccess?.invoke(topic)
                }
            }
    }

    private fun scheduleRetry() {
        if (retryCount >= MAX_RETRY) {
            MqttLogger.error("Max retry reached ($MAX_RETRY). Giving up.")
            return
        }
        retryJob?.cancel()
        retryJob = scope.launch {
            retryCount++
            // FIX 4: Tambah cap 30 detik agar tidak menunggu terlalu lama
            val delayMs = minOf(RETRY_DELAY_MS * retryCount, 30_000L)
            Log.i(TAG, "Retry #$retryCount in ${delayMs / 1000}s...")
            delay(delayMs)
            if (!isIntentionallyStopped) doConnect()
        }
    }

    private fun buildRadioDataJson(p: RadioDataPayload): String {
        return JSONObject().apply {
            put("timestamp", p.timestamp)
            put("serial_number", p.serialNumber)
            put("android_id", p.androidId)
            put("app_version", p.appVersion)
            put("identity", JSONObject().apply {
                put("id", p.identity.id)
                put("nrp", p.identity.nrp)
                put("name", p.identity.name)
                put("satuan", p.identity.satuan)
                put("batalyon", p.identity.batalyon)
                put("peleton", p.identity.peleton)
                put("regu", p.identity.regu)
                put("kompi", p.identity.kompi)
                put("divisi", p.identity.divisi)
                put("brigade", p.identity.brigade)
                put("team", p.identity.team)
                put("rank", p.identity.rank)
                put("unit", p.identity.unit)
                put("avatar_url", p.identity.avatarUrl)
            })
            put("gps", JSONObject().apply {
                put("gps_timestamp", p.gps.gpsTimestamp)
                put("latitude", p.gps.latitude)
                put("longitude", p.gps.longitude)
                put("accuracy", p.gps.accuracy)
            })
            put("radio_health", JSONObject().apply {
                put("heartrate_timestamp", p.radioHealth.heartrateTimestamp)
                put("heartrate", p.radioHealth.heartrate)
            })
            put("battery", JSONObject().apply {
                put("battery_timestamp", p.battery.batteryTimestamp)
                put("level", p.battery.level)
            })
            put("stream", JSONObject().apply {
                put("rtmp_url", p.stream.rtmpUrl)
            })
        }.toString()
    }

    private fun buildRadioSosJson(p: RadioSosPayload): String {
        return JSONObject().apply {
            put("timestamp", p.timestamp)
            put("serial_number", p.serialNumber)
            put("android_id", p.androidId)
            put("id", p.id)
            put("name", p.name)
            put("avatar", p.avatarUrl)
            put("sos", p.sos)
            put("latitude", p.latitude)
            put("longitude", p.longitude)
            put("accuracy", p.accuracy)
        }.toString()
    }

    private fun buildBodycamDataJson(p: BodycamDataPayload): String {
        return JSONObject().apply {
            put("timestamp", p.timestamp)
            put("serial_number", p.serialNumber)
            put("android_id", p.androidId)
            put("id", p.id)
            put("name", p.name)
            put("stream", p.stream)
            put("rtmp_url", p.streamUrl)
        }.toString()
    }

    private fun buildBodycamSosJson(p: BodycamSosPayload): String {
        return JSONObject().apply {
            put("timestamp", p.timestamp)
            put("serial_number", p.serialNumber)
            put("android_id", p.androidId)
            put("id", p.id)
            put("name", p.name)
            put("avatar", p.avatarUrl)
            put("sos", p.sos)
            put("latitude", p.latitude)
            put("longitude", p.longitude)
            put("accuracy", p.accuracy)
        }.toString()
    }
}