package com.example.personeltracking2026.data.model

import com.google.gson.annotations.SerializedName

// ─── TOPIC: radio/data ───────────────────────────────────────────────────────

data class RadioDataPayload(
    @SerializedName("timestamp")      val timestamp: Long,
    @SerializedName("serial_number")  val serialNumber: String,
    @SerializedName("android_id")      val androidId: String,
    @SerializedName("app_version")    val appVersion: String,
    @SerializedName("identity")       val identity: IdentityPayload,
    @SerializedName("gps")            val gps: GpsPayload,
    @SerializedName("radio_health")   val radioHealth: RadioHealthPayload,
    @SerializedName("battery")        val battery: BatteryPayload,
    @SerializedName("stream")         val stream: StreamPayload
)

data class IdentityPayload(
    @SerializedName("id")         val id: String,
    @SerializedName("nrp")        val nrp: String,
    @SerializedName("name")       val name: String,
    @SerializedName("satuan")     val satuan: String,
    @SerializedName("batalyon")   val batalyon: String,
    @SerializedName("peleton")    val peleton: String,
    @SerializedName("regu")       val regu: String,
    @SerializedName("kompi")      val kompi: String,
    @SerializedName("divisi")     val divisi: String,
    @SerializedName("brigade")    val brigade: String,
    @SerializedName("team")       val team: String,
    @SerializedName("rank")       val rank: String,
    @SerializedName("unit")       val unit: String,
    @SerializedName("avatar_url") val avatarUrl: String
)

data class GpsPayload(
    @SerializedName("gps_timestamp")  val gpsTimestamp: Long,
    @SerializedName("latitude")       val latitude: Double,
    @SerializedName("longitude")      val longitude: Double,
    @SerializedName("accuracy")       val accuracy: Float,
)

data class RadioHealthPayload(
    @SerializedName("heartrate_timestamp")  val heartrateTimestamp: Long,
    @SerializedName("heartrate")            val heartrate: Int
)

data class BatteryPayload(
    @SerializedName("battery_timestamp") val batteryTimestamp: Long,
    @SerializedName("level")             val level: Int
)

data class StreamPayload(
    @SerializedName("rtmp_url") val rtmpUrl: String
)

// ─── TOPIC: radio/sos ────────────────────────────────────────────────────────

data class RadioSosPayload(
    @SerializedName("timestamp")     val timestamp: Long,
    @SerializedName("serial_number") val serialNumber: String,
    @SerializedName("android_id")     val androidId: String,
    @SerializedName("id")            val id: String,
    @SerializedName("name")          val name: String,
    @SerializedName("avatar")        val avatarUrl: String,
    @SerializedName("sos")           val sos: Int,
    @SerializedName("latitude")      val latitude: Double,
    @SerializedName("longitude")     val longitude: Double,
    @SerializedName("accuracy")      val accuracy: Float
)

// ─── TOPIC: bodycam/data ────────────────────────────────────────────────────────

data class BodycamDataPayload(
    @SerializedName("timestamp")     val timestamp: Long,
    @SerializedName("serial_number") val serialNumber: String,
    @SerializedName("android_id")    val androidId: String,
    @SerializedName("id")            val id: String,
    @SerializedName("name")          val name: String,
    @SerializedName("stream")        val stream: Int,
    @SerializedName("rtmp_url")      val streamUrl: String
)

// ─── TOPIC: bodycam/sos ────────────────────────────────────────────────────────

data class BodycamSosPayload(
    @SerializedName("timestamp")     val timestamp: Long,
    @SerializedName("serial_number") val serialNumber: String,
    @SerializedName("android_id")    val androidId: String,
    @SerializedName("id")            val id: String,
    @SerializedName("name")          val name: String,
    @SerializedName("avatar")        val avatarUrl: String,
    @SerializedName("sos")           val sos: Int,
    @SerializedName("latitude")      val latitude: Double,
    @SerializedName("longitude")     val longitude: Double,
    @SerializedName("accuracy")      val accuracy: Float
)