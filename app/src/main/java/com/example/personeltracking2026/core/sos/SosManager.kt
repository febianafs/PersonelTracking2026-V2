package com.example.personeltracking2026.core.sos

import com.example.personeltracking2026.core.mqtt.MqttManager
import com.example.personeltracking2026.core.mqtt.MqttPayloadBuilder
import com.example.personeltracking2026.core.session.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SosManager {
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    enum class DeviceType {
        RADIO,
        BODYCAM
    }

    private var mqttManager: MqttManager? = null
    private var sessionManager: SessionManager? = null
    private var serialNumber: String = "unknown"
    private var androidId: String = "unknown"
    private var deviceType: DeviceType = DeviceType.RADIO
    private var getLocation: (() -> Triple<Double, Double, Float>)? = null

    fun init(
        mqtt: MqttManager,
        session: SessionManager,
        serial: String,
        id: String,
        type: DeviceType,
        locationProvider: () -> Triple<Double, Double, Float>
    ) {
        mqttManager    = mqtt
        sessionManager = session
        serialNumber   = serial
        androidId      = id
        deviceType     = type
        getLocation    = locationProvider
    }

    fun activate() { _isActive.value = true; publishSos(1) }
    fun deactivate() { _isActive.value = false; publishSos(0) }
    fun toggle() { if (_isActive.value) deactivate() else activate() }

    private fun publishSos(sosValue: Int) {
        val mqtt    = mqttManager ?: return
        val session = sessionManager ?: return
        val (lat, lon, accuracy) = getLocation?.invoke() ?: return

        when (deviceType) {
            DeviceType.RADIO -> {
                val payload = MqttPayloadBuilder.buildRadioSosPayload(
                    session      = session,
                    serialNumber = serialNumber,
                    androidId    = androidId,
                    lat          = lat,
                    lon          = lon,
                    acc          = accuracy,
                    sos          = sosValue
                )
                mqtt.publishRadioSos(payload)
            }

            DeviceType.BODYCAM -> {
                val payload = MqttPayloadBuilder.buildBodycamSosPayload(
                    session      = session,
                    serialNumber = serialNumber,
                    androidId    = androidId,
                    lat          = lat,
                    lon          = lon,
                    acc          = accuracy,
                    sos          = sosValue
                )
                mqtt.publishBodycamSos(payload)
            }
        }
    }
}