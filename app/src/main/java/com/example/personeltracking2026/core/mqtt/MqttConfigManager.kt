package com.example.personeltracking2026.core.mqtt

import android.content.Context

class MqttConfigManager(context: Context) {

    private val prefs = context.getSharedPreferences("mqtt_config", Context.MODE_PRIVATE)

    fun save(config: MqttConfig) {
        prefs.edit()
            .putString("host",     config.host)
            .putInt("tcp_port",    config.tcpPort)
            .putInt("ws_port",     config.wsPort)
            .putString("username", config.username)
            .putString("password", config.password)
            .putBoolean("use_ws",  config.useWebSocket)
            .apply()
    }

    fun load(): MqttConfig {
        return MqttConfig(
            host         = prefs.getString("host",     "76.13.20.253") ?: "76.13.20.253",
            tcpPort      = prefs.getInt("tcp_port",    1883),
            wsPort       = prefs.getInt("ws_port",     9001),
            username     = prefs.getString("username", "kodau")        ?: "kodau",
            password     = prefs.getString("password", "kodau2026")    ?: "kodau2026",
            useWebSocket = prefs.getBoolean("use_ws",  true)
        )
    }
}