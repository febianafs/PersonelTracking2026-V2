package com.example.personeltracking2026.core.mqtt

import com.example.personeltracking2026.core.session.SessionManager
import com.example.personeltracking2026.data.model.BatteryPayload
import com.example.personeltracking2026.data.model.BodycamDataPayload
import com.example.personeltracking2026.data.model.BodycamSosPayload
import com.example.personeltracking2026.data.model.GpsPayload
import com.example.personeltracking2026.data.model.IdentityPayload
import com.example.personeltracking2026.data.model.RadioDataPayload
import com.example.personeltracking2026.data.model.RadioHealthPayload
import com.example.personeltracking2026.data.model.RadioSosPayload
import com.example.personeltracking2026.data.model.StreamPayload

/**
 * Helper untuk build payload MQTT dari state yang ada di app.
 * Pisahkan dari MqttManager agar mudah di-test dan di-modify.
 */
object MqttPayloadBuilder {

    /**
     * Bangun payload radio/data.
     *
     * @param session         SessionManager untuk ambil user info
     * @param serialNumber    Serial number perangkat (IMEI/device ID)
     * @param lat             Latitude dari GPS
     * @param lon             Longitude dari GPS
     * @param gpsTimestamp    Timestamp epoch saat fix GPS diterima
     * @param heartrate       BPM dari BLE smartwatch (0 jika belum ada)
     * @param heartrateTs     Timestamp epoch saat HR diterima
     * @param batteryLevel    Level baterai 0-100
     * @param appVersion      Versi aplikasi yang sedang berjalan
     * @param rtmpUrl         Url livestream dari setiap device
     */
    fun buildRadioDataPayload(
        session: SessionManager,
        serialNumber: String,
        androidId: String,
        lat: Double,
        lon: Double,
        acc: Float,
        gpsTimestamp: Long,
        heartrate: Int,
        heartrateTs: Long,
        batteryLevel: Int,
        appVersion: String,
        rtmpUrl: String
    ): RadioDataPayload {
        val nowSec = System.currentTimeMillis() / 1000

        val identity = IdentityPayload(
            id        = session.getUserId()?.toString() ?: "",
            nrp       = session.getNrp() ?: "",
            name      = session.getName() ?: "",
            satuan     = session.getSatuan(),
            batalyon   = session.getBatalyon(),
            peleton    = session.getPeleton(),
            regu       = session.getRegu(),
            kompi      = session.getKompi(),
            divisi     = session.getDivisi(),
            brigade    = session.getBrigade(),
            team       = session.getTeam(),
            unit       = session.getUnit(),
            rank       = session.getRank(),
            avatarUrl  = session.getAvatarUrl()
        )

        return RadioDataPayload(
            timestamp    = nowSec,
            serialNumber = serialNumber,
            androidId    = androidId,
            appVersion   = appVersion,
            identity     = identity,
            gps = GpsPayload(
                gpsTimestamp = gpsTimestamp,
                latitude = lat,
                longitude = lon,
                accuracy = acc
            ),
            radioHealth = RadioHealthPayload(
                heartrateTimestamp = heartrateTs,
                heartrate = heartrate
            ),
            battery = BatteryPayload(
                batteryTimestamp = nowSec,
                level = batteryLevel
            ),
            stream = StreamPayload(
                rtmpUrl = rtmpUrl
            )
        )
    }

    /**
     * Bangun payload radio/sos.
     *
     * @param session       SessionManager
     * @param serialNumber  Serial number perangkat
     * @param lat           Latitude posisi saat SOS
     * @param lon           Longitude posisi saat SOS
     */
    fun buildRadioSosPayload(
        session: SessionManager,
        serialNumber: String,
        androidId: String,
        lat: Double,
        lon: Double,
        acc: Float,
        sos: Int
    ): RadioSosPayload {
        val nowSec = System.currentTimeMillis() / 1000
        return RadioSosPayload(
            timestamp    = nowSec,
            serialNumber = serialNumber,
            androidId    = androidId,
            id           = session.getUserId()?.toString() ?: "",
            name         = session.getName() ?: "",
            avatarUrl    = session.getAvatarUrl() ?: "",
            sos          = sos,
            latitude     = lat,
            longitude    = lon,
            accuracy     = acc
        )
    }

    fun buildBodycamDataPayload(
        session: SessionManager,
        serialNumber: String,
        androidId: String,
        streamUrl: String,
        stream: Int
    ): BodycamDataPayload {
        val nowSec = System.currentTimeMillis() / 1000

        return BodycamDataPayload(
            timestamp    = nowSec,
            serialNumber = serialNumber,
            androidId    = androidId,
            id           = session.getUserId()?.toString() ?: "",
            name         = session.getName() ?: "",
            stream       = stream,
            streamUrl    = streamUrl
        )
    }

    fun buildBodycamSosPayload(
        session: SessionManager,
        serialNumber: String,
        androidId: String,
        lat: Double,
        lon: Double,
        acc: Float,
        sos: Int
    ): BodycamSosPayload {
        val nowSec = System.currentTimeMillis() / 1000

        return BodycamSosPayload(
            timestamp    = nowSec,
            serialNumber = serialNumber,
            androidId    = androidId,
            id           = session.getUserId()?.toString() ?: "",
            name         = session.getName() ?: "",
            avatarUrl    = session.getAvatarUrl() ?: "",
            sos          = sos,
            latitude     = lat,
            longitude    = lon,
            accuracy     = acc
        )
    }
}