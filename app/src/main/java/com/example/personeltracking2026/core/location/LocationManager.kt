package com.example.personeltracking2026.core.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.Handler
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class AppLocationManager(private val context: Context) {

    // Callback ke Activity
    var onLocationUpdate: ((lat: Double, lon: Double, accuracy: Float, source: String) -> Unit)? = null
    var onLocationError: ((message: String) -> Unit)? = null

    private val isPlayServicesAvailable: Boolean
        get() = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    // FusedLocationProvider (Google Play Services)
    private var fusedClient: FusedLocationProviderClient? = null
    private var fusedCallback: LocationCallback? = null

    // Android LocationManager (fallback)
    private var locationManager: LocationManager? = null
    private var gpsListener: LocationListener? = null
    private var networkListener: LocationListener? = null
    private var bestLocation: Location? = null
    private var satelliteCount = 0
    private var intervalMs: Long = 5000L // default 5 detik

    fun setInterval(ms: Long) {
        intervalMs = ms
    }

    fun startUpdates() {
        if (!hasPermission()) {
            onLocationError?.invoke("Location permission not granted")
            return
        }

        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        try {
            locationManager?.registerGnssStatusCallback(
                gnssCallback,
                Handler(Looper.getMainLooper())
            )
        } catch (e: SecurityException) {
            onLocationError?.invoke("GNSS permission denied")
        }

        if (isPlayServicesAvailable) {
            startFusedUpdates()
        } else {
            startLegacyUpdates()
        }
    }

    fun stopUpdates() {
        locationManager?.unregisterGnssStatusCallback(gnssCallback)
        stopFusedUpdates()
        stopLegacyUpdates()
    }

    // ─── FUSED (Google Play Services) ───────────────────────────────────────

    private fun startFusedUpdates() {
        fusedClient = LocationServices.getFusedLocationProviderClient(context)

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()

        fusedCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.locations.lastOrNull() ?: return
                onLocationUpdate?.invoke(
                    location.latitude,
                    location.longitude,
                    location.accuracy,
                    "Fused"
                )
            }
        }

        try {
            fusedClient?.requestLocationUpdates(
                request,
                fusedCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            onLocationError?.invoke("Location permission denied")
        }
    }

    private fun stopFusedUpdates() {
        fusedCallback?.let { fusedClient?.removeLocationUpdates(it) }
        fusedCallback = null
        fusedClient = null
    }

    // ─── LEGACY (Android LocationManager) ───────────────────────────────────

    private fun startLegacyUpdates() {

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // Ambil yang paling akurat antara GPS dan Network
                if (isBetterLocation(location, bestLocation)) {
                    bestLocation = location
                    onLocationUpdate?.invoke(
                        location.latitude,
                        location.longitude,
                        location.accuracy,
                        if (location.provider == LocationManager.GPS_PROVIDER) "GPS" else "Network"
                    )
                }
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            // GPS provider
            if (locationManager!!.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                gpsListener = locationListener
                locationManager!!.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMs,
                    1f,
                    gpsListener!!,
                    Looper.getMainLooper()
                )
            }

            // Network provider (WiFi + Cell Tower)
            if (locationManager!!.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                networkListener = locationListener
                locationManager!!.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    intervalMs,
                    1f,
                    networkListener!!,
                    Looper.getMainLooper()
                )
            }

            // Kalau keduanya tidak tersedia
            if (gpsListener == null && networkListener == null) {
                onLocationError?.invoke("No location provider available")
            }

        } catch (e: SecurityException) {
            onLocationError?.invoke("Location permission denied")
        }
    }

    private fun stopLegacyUpdates() {
        gpsListener?.let { locationManager?.removeUpdates(it) }
        networkListener?.let { locationManager?.removeUpdates(it) }
        gpsListener = null
        networkListener = null
        locationManager = null
        bestLocation = null
    }
    // ─── HELPER ─────────────────────────────────────────────────────────────

    private fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    // Algoritma cek apakah location baru lebih baik dari yang lama
    private fun isBetterLocation(location: Location, currentBest: Location?): Boolean {
        if (currentBest == null) return true

        val timeDelta = location.time - currentBest.time
        val isSignificantlyNewer = timeDelta > 30 * 1000   // Ubah: 2 menit → 30 detik
        val isSignificantlyOlder = timeDelta < -(30 * 1000)
        val isNewer = timeDelta > 0

        if (isSignificantlyNewer) return true
        if (isSignificantlyOlder) return false

        val accuracyDelta = (location.accuracy - currentBest.accuracy).toInt()
        val isMoreAccurate = accuracyDelta < 0
        val isSignificantlyLessAccurate = accuracyDelta > 200

//        val isFromSameProvider = location.provider == currentBest.provider

        return when {
            isMoreAccurate -> true
            isNewer && !isSignificantlyLessAccurate -> true
            else -> false
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var usedInFix = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) usedInFix++
            }
            satelliteCount = usedInFix
            Log.d("GNSS_DEBUG", "Total: ${status.satelliteCount}, Used: $usedInFix")
        }
    }
}