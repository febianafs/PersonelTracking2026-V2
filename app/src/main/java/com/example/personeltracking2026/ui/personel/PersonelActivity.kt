package com.example.personeltracking2026.ui.personel

import android.Manifest
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startActivity
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.example.personeltracking2026.App
import com.example.personeltracking2026.R
import com.example.personeltracking2026.core.base.BaseActivity
import com.example.personeltracking2026.core.device.DeviceMode
import com.example.personeltracking2026.core.map.MapTypeManager
import com.example.personeltracking2026.core.mqtt.MqttConfigManager
import com.example.personeltracking2026.core.mqtt.MqttPayloadBuilder
import com.example.personeltracking2026.core.mqtt.MqttReconnectManager
import com.example.personeltracking2026.core.navigation.LastScreen
import com.example.personeltracking2026.core.session.SessionManager
import com.example.personeltracking2026.core.sos.SosManager
import com.example.personeltracking2026.data.model.LocationData
import com.example.personeltracking2026.data.model.PersonelData
import com.example.personeltracking2026.data.model.getClassification
import com.example.personeltracking2026.data.repository.LocationRepository
import com.example.personeltracking2026.data.repository.PersonelRepository
import com.example.personeltracking2026.databinding.ActivityPersonelBinding
import com.example.personeltracking2026.ui.bluetooth.BluetoothLeService
import com.example.personeltracking2026.ui.settings.SettingsActivity
import com.example.personeltracking2026.utils.DeviceIdentityManager
import com.example.personeltracking2026.utils.drawableToBitmap
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import me.relex.circleindicator.CircleIndicator3
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point


class PersonelActivity : BaseActivity() {

    private lateinit var binding: ActivityPersonelBinding
    private lateinit var mqttPrefs: SharedPreferences
    private lateinit var sessionManager: SessionManager
//    private lateinit var mapView: MapView
    private lateinit var pagerAdapter: TopPagerAdapter
    private lateinit var reconnectManager: MqttReconnectManager

    private val viewModel: PersonelViewModel by viewModels {
        PersonelViewModel.Factory(
            application,
            PersonelRepository(),
            (application as App).locationRepository,
            SessionManager(this)
        )
    }

    private val zoneCenterLat    = -7.868729
    private val zoneCenterLon    = 105.643117
    private val zonaRadiusMeters = 500.0
    private var currentMapType   = MapTypeManager.MapType.STANDARD
    private var currentLat = zoneCenterLat
    private var currentLon = zoneCenterLon
    private var mapLibreMap: org.maplibre.android.maps.MapLibreMap? = null
    private var isLocationStarted = false
    private var firstLocationReceived = false
    private var geoJsonSource: GeoJsonSource? = null
    private var lastAccepted: LocationData? = null
    private val smoothWindow = ArrayDeque<LocationData>()

    // ─── SOS ─────────────────────────────────────────────────────────────────
    private var markerBlinkJob: Job? = null

    // ─── PERMISSION ──────────────────────────────────────────────────────────

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            requestBackgroundLocation()
        }
        else Toast.makeText(this, "Location permission required", Toast.LENGTH_LONG).show()
    }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                locationPermissionRequest.launch(
                    arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                )
            }
        }
    }

    // ─── LIFECYCLE ───────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        MapLibre.getInstance(
            this,
            null,
            org.maplibre.android.WellKnownTileServer.MapLibre
        )

        binding        = ActivityPersonelBinding.inflate(layoutInflater)
        sessionManager = SessionManager(this)
        mqttPrefs      = getSharedPreferences("mqtt_settings", MODE_PRIVATE)

        setContentView(binding.root)

        requestBatteryOptimizationExemption()

        pagerAdapter = TopPagerAdapter()
        binding.viewPagerTop.adapter = pagerAdapter

        binding.indicator?.setViewPager(binding.viewPagerTop)

        (binding.viewPagerTop.getChildAt(0) as RecyclerView)
            .itemAnimator = null

        pagerAdapter.onFullDataClick = {
            personelBottomSheet()
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.btnOverflow.setOnClickListener { showOverflowMenu(it) }

        reconnectManager = MqttReconnectManager(this, viewModel.mqttManager)

        val savedType = getSharedPreferences("map_settings", MODE_PRIVATE)
            .getString("map_type", MapTypeManager.MapType.STANDARD.name)

        val newType = MapTypeManager.MapType.values()
            .firstOrNull { it.name == savedType }
            ?: MapTypeManager.MapType.STANDARD

        currentMapType = newType

//        setupMap()

        val deviceManager = DeviceIdentityManager(this)
        val identity = deviceManager.getIdentity()

        if (deviceManager.isAutoGenerated()) {
            showSerialDialog()
        }

        val app = application as App

        app.currentMode = DeviceMode.RADIO_BODYCAM

        // FIX: SosManager di-init ulang di sini dengan locationProvider
        // yang membaca koordinat dari App level (bukan hanya dari PersonelActivity)
        // Ini memastikan SosManager selalu punya koordinat terbaru,
        // bahkan saat SOS dipencet dari SettingsActivity
        SosManager.init(
            mqtt             = app.mqttManager,
            session          = sessionManager,
            serial           = identity.serial,
            id               = identity.androidId,
            type             = SosManager.DeviceType.RADIO,
            locationProvider = { Triple(app.currentLat, app.currentLon, app.currentAccuracy) }
        )

//        binding.btnZoomIn.setOnClickListener {
//            mapLibreMap?.animateCamera(
//                org.maplibre.android.camera.CameraUpdateFactory.zoomIn()
//            )
//        }

//        binding.btnZoomOut.setOnClickListener {
//            mapLibreMap?.animateCamera(
//                org.maplibre.android.camera.CameraUpdateFactory.zoomOut()
//            )
//        }

        setupVitalSignsInitial()
        updateMqttUI()
        setupClickListeners()
        setupSwipeRefresh()
        observeAllStates()
        loadPersonelData()
        requestLocationPermission()
    }

    override fun onStart() {
        super.onStart()

        viewModel.registerBatteryReceiver(this)
//        binding.mapView.onStart()
        updateMqttUI()

        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            requestLocationPermission()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            reconnectManager.start()
        }
        lifecycleScope.launch {
            startLocationUpdates()
        }
    }

    override fun onStop() {
        super.onStop()

        reconnectManager.stop()

        viewModel.unregisterBatteryReceiver(this)
//        binding.mapView.onStop()
    }

    override fun onResume() {
        super.onResume()
        (application as App).currentMode = DeviceMode.RADIO_BODYCAM
        SessionManager(this).saveLastScreen(LastScreen.PERSONEL)
//        binding.mapView.onResume()
        val savedType = getSharedPreferences("map_settings", MODE_PRIVATE)
            .getString("map_type", MapTypeManager.MapType.STANDARD.name)

        val newType = MapTypeManager.MapType.values()
            .firstOrNull { it.name == savedType }
            ?: MapTypeManager.MapType.STANDARD

        if (newType != currentMapType) {
            currentMapType = newType
//            applyMapType(newType)
        }

        val app = application as App
        val identity = DeviceIdentityManager(this).getIdentity() ?: return

        SosManager.init(
            mqtt             = app.mqttManager,
            session          = sessionManager,
            serial           = identity.serial,
            id               = identity.androidId,
            type             = SosManager.DeviceType.RADIO,
            locationProvider = { Triple(app.currentLat, app.currentLon, app.currentAccuracy) }
        )
    }

    override fun onPause() {
        super.onPause()
//        binding.mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        markerBlinkJob?.cancel()
//        binding.mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
//        binding.mapView.onLowMemory()
    }

    // ─── OBSERVERS ───────────────────────────────────────────────────────────

    private fun observeAllStates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // SOS — khusus marker blink + publish MQTT
                launch {
                    SosManager.isActive.collect { isActive ->
                        if (isActive) {
                            startMarkerBlink()
                        } else {
                            stopMarkerBlink()
                        }
                    }
                }

                // Lokasi GPS
                launch {
                    viewModel.locationState.collect { state ->

                        val accuracy = state.data?.accuracy ?: Float.MAX_VALUE

                        val (signalText) = when {
                            accuracy <= 10 -> "Strong" to "#4FC3F7"
                            accuracy <= 30 -> "Medium" to "#FFD740"
                            accuracy <= 100 -> "Weak" to "#FF5252"
                            else -> "No Signal" to "#FF5252"
                        }

                        if (pagerAdapter.gpsSignal != signalText) {
                            pagerAdapter.gpsSignal = signalText
                            pagerAdapter.notifyItemChanged(1, "GPS")
                        }

                        state.data?.let {
                            if (!firstLocationReceived) {
                                firstLocationReceived = true
                            }
                        }

                        state.data?.let {
//                            updateCoordinates(it.lat, it.lon)

                            // FIX: Update koordinat ke App level
                            // agar SosManager bisa baca koordinat terbaru
                            // dari Activity manapun (termasuk SettingsActivity)
                            val app = application as App
                            app.currentLat      = it.lat
                            app.currentLon      = it.lon
                            app.currentAccuracy = it.accuracy

                            if (!SosManager.isActive.value) {
//                                updateMarker(it.lat, it.lon)
                            }
                            Log.d(
                                "GPS_UI",
                                "lat=${it.lat}, lon=${it.lon}"
                            )
                            pagerAdapter.latitude = it.lat
                            pagerAdapter.longitude = it.lon
                            pagerAdapter.notifyItemChanged(0)
                        }
                    }
                }

                // Baterai
                launch {
                    viewModel.batteryState.collect { updateBattery(it.percent) }
                }

                // Profil personel
                launch {
                    viewModel.personelState.collect { state ->
                        when (state) {
                            is PersonelState.Loading -> {
                                binding.swipeRefresh?.isRefreshing = true
                            }

                            is PersonelState.Success -> {
                                binding.swipeRefresh?.isRefreshing = false
                                bindPersonelData(state.data)
                            }

                            is PersonelState.Error -> {
                                binding.swipeRefresh?.isRefreshing = false

                                pagerAdapter.name = sessionManager.getName() ?: "-"
                                pagerAdapter.nrp = sessionManager.getNrp().ifBlank { "-" }
                                pagerAdapter.rank = sessionManager.getRank().ifBlank { "-" }

                                pagerAdapter.notifyItemChanged(0)
                            }
                        }
                    }
                }

                // Last sync
                launch {
                    while (true) {
                        val lastSyncTime = viewModel.lastSyncTime.value

                        val diffSec = (System.currentTimeMillis() - lastSyncTime) / 1000

                        val (text, color) = when {
                            lastSyncTime == 0L -> "Belum sync"          to "#9E9E9E"
                            diffSec < 2        -> "Just now"            to "#69F0AE"
                            diffSec < 60       -> "${diffSec}s ago"     to "#69F0AE"
                            diffSec < 300      -> "${diffSec / 60}m ago" to "#FFD740"
                            else               -> "${diffSec / 60}m ago" to "#FF5252"
                        }

                        pagerAdapter.lastSync    = text
                        pagerAdapter.statusColor = color
                        pagerAdapter.notifyItemChanged(0)
                        pagerAdapter.notifyItemChanged(1)
                        pagerAdapter.notifyItemChanged(2)

                        delay(1000)
                    }
                }

                // Heart rate dari BLE
                launch {
                    launch {
                        BluetoothLeService.connectionState.collect { state ->
                            val isConnected = state == BluetoothLeService.ConnectionState.CONNECTED
                            val deviceName  = BluetoothLeService.connectedDevice.value?.name ?: "--"

                            pagerAdapter.bleConnected  = isConnected
                            pagerAdapter.bleDeviceName = if (isConnected) deviceName else "--"
                            pagerAdapter.notifyItemChanged(1)
                        }
                    }

                    // Observe BPM realtime
//                    launch {
//                        BluetoothLeService.bpmValue.collect { bpm ->
//                            val app = application as App
//                            // simpan HR terbaru
//                            app.currentHeartRate = bpm
//                            app.currentHeartRateTs = System.currentTimeMillis()
//                            // update ViewModel untuk MQTT
//                            viewModel.updateHeartRate(
//                                bpm = bpm,
//                                deviceName = BluetoothLeService.connectedDevice.value?.name ?: ""
//                            )
//                        }
//                    }

                    // Refresh UI + cek expired
                    launch {

                        while (true) {
                            val app = application as App
                            val isExpired =
                                System.currentTimeMillis() - app.currentHeartRateTs > 30_000
                            val finalHr = if (isExpired) 0 else app.currentHeartRate
                            pagerAdapter.bleBpm = finalHr
                            pagerAdapter.notifyItemChanged(1)
                            delay(1000)
                        }
                        BluetoothLeService.bpmValue.collect { bpm ->
                            pagerAdapter.bleBpm = bpm
                            pagerAdapter.notifyItemChanged(1)
                        }
                    }
                }
            }
        }
    }

    // ─── SOS (marker only — toolbar dihandle BaseActivity) ───────────────────

    private fun startMarkerBlink() {
        markerBlinkJob?.cancel()
        markerBlinkJob = lifecycleScope.launch {
            var toggle = false
            while (SosManager.isActive.value) {
                updateMarkerWithColor(toggle)
                toggle = !toggle
                delay(500)
            }
        }
    }

    private fun stopMarkerBlink() {
        markerBlinkJob?.cancel()
        markerBlinkJob = null
        updateMarkerWithColor(true)
    }

    // ─── PERSONEL ────────────────────────────────────────────────────────────

    private fun loadPersonelData() {
        val token  = sessionManager.getToken()  ?: return
        val userId = sessionManager.getUserId() ?: return
        viewModel.loadPersonelDetail(userId, token)
    }

    private fun resolveCmsImageUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null

        return if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "https://cms.kodauemws.com/images/${path.trimStart('/')}"
        }
    }

    private fun bindPersonelData(data: PersonelData) {
        val avatarFromApi = resolveCmsImageUrl(data.avatar_url ?: data.image)
        val avatarFromSession = sessionManager.getAvatarUrl().takeIf { it.isNotBlank() }

        val finalAvatar = avatarFromApi ?: avatarFromSession

        Log.d("AVATAR_CHECK", "avatarFromApi = $avatarFromApi")
        Log.d("AVATAR_CHECK", "avatarFromSession = $avatarFromSession")
        Log.d("AVATAR_CHECK", "finalAvatar = $finalAvatar")

        pagerAdapter.avatarUrl = finalAvatar

        finalAvatar?.let {
            sessionManager.saveAvatar(it)
        }

        val finalName = data.full_name
            ?: data.name
            ?: sessionManager.getName()
            ?: "-"

        pagerAdapter.name = finalName
        sessionManager.saveName(finalName)

        pagerAdapter.nrp = data.nrp
            ?: sessionManager.getNrp().ifBlank { "-" }

        pagerAdapter.rank = data.rank?.name
            ?: sessionManager.getRank().ifBlank { "-" }

        pagerAdapter.notifyItemChanged(0)
    }

    private fun updateBattery(percent: Int) {
        pagerAdapter.battery = percent
        pagerAdapter.notifyItemChanged(1)
    }

    private fun personelBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_personel, null)

        val personel = (viewModel.personelState.value as? PersonelState.Success)?.data

        val name = personel?.full_name
            ?: personel?.name
            ?: sessionManager.getName()
            ?: "-"

        val nrp = personel?.nrp?.takeIf { it.isNotBlank() }
            ?: sessionManager.getNrp().ifBlank { "-" }

        // Ambil dari classification dulu, fallback ke field langsung, fallback ke session
        val rank = personel.getClassification("Rank")
            .ifBlank { personel?.rank?.name ?: "" }
            .ifBlank { sessionManager.getRank() }
            .ifBlank { "-" }

        val unit = personel.getClassification("Unit")
            .ifBlank { personel?.unit?.name ?: "" }
            .ifBlank { sessionManager.getUnit() }
            .ifBlank { "-" }

        val regu = personel.getClassification("Regu")
            .ifBlank { personel?.regu?.name ?: "" }
            .ifBlank { sessionManager.getRegu() }
            .ifBlank { "-" }

        view.findViewById<TextView>(R.id.tvName).text  = name
        view.findViewById<TextView>(R.id.tvNRP).text   = nrp
        view.findViewById<TextView>(R.id.tvRank).text  = rank
        view.findViewById<TextView>(R.id.tvUnit).text  = unit
        view.findViewById<TextView>(R.id.tvSquad).text = regu

        val avatarUrl = resolveCmsImageUrl(personel?.avatar_url ?: personel?.image)
            ?: sessionManager.getAvatarUrl().takeIf { it.isNotBlank() }

        val imgProfile = view.findViewById<ImageView>(R.id.imgProfile)
        if (!avatarUrl.isNullOrBlank()) {
            Glide.with(this)
                .load(avatarUrl)
                .placeholder(R.drawable.ic_avatar)
                .error(R.drawable.ic_avatar)
                .dontAnimate()
                .into(imgProfile)
        } else {
            imgProfile.setImageResource(R.drawable.ic_avatar)
        }

        dialog.setContentView(view)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    // ─── MAP ─────────────────────────────────────────────────────────────────

//    private fun setupMap() {
//        mapView = binding.mapView
//        mapView.onCreate(null)
//
//        mapView.getMapAsync { map ->
//            mapLibreMap = map
//
//            val styleUrl = MapTypeManager.getStyleUrl(currentMapType)
//            map.setStyle(
//                Style.Builder().fromUri(styleUrl)
//            ) {
//                val point = LatLng(currentLat, currentLon)
//
//                map.cameraPosition = CameraPosition.Builder()
//                    .target(point)
//                    .zoom(15.0)
//                    .build()
//
//                val style = it
//
//                geoJsonSource = GeoJsonSource(
//                    "personel-source",
//                    Point.fromLngLat(currentLon, currentLat)
//                )
//                style.addSource(geoJsonSource!!)
//
//                val drawableRed = ContextCompat.getDrawable(this, R.drawable.ic_location_pin)!!
//                drawableRed.setTint(Color.parseColor("#FF1744"))
//                val bitmapRed = drawableToBitmap(drawableRed)
//
//                style.addImage("marker-red", bitmapRed)
//
//                val drawablePink = ContextCompat.getDrawable(this, R.drawable.ic_location_pin)!!
//                drawablePink.setTint(Color.parseColor("#FF8A80"))
//                val bitmapPink = drawableToBitmap(drawablePink)
//
//                style.addImage("marker-pink", bitmapPink)
//
//                val symbolLayer = SymbolLayer("personel-layer", "personel-source")
//                    .withProperties(
//                        iconImage("marker-red"),
//                        iconAllowOverlap(true),
//                        iconIgnorePlacement(true)
//                    )
//                style.addLayer(symbolLayer)
//            }
//        }
//        Log.d("INIT", "MAP READY")
//    }

//    private fun updateMarker(lat: Double, lon: Double) {
//        geoJsonSource?.setGeoJson(
//            Point.fromLngLat(lon, lat)
//        )
//
//        val newLoc = LocationData(lat, lon, 0f, "")
//        val last = lastAccepted
//
//        if (last == null || distance(last, newLoc) > 5) {
//            mapLibreMap?.moveCamera(
//                CameraUpdateFactory.newLatLng(LatLng(lat, lon))
//            )
//            lastAccepted = newLoc
//        }
//    }

    private fun updateMarkerWithColor(useRed: Boolean) {
        val layer = mapLibreMap?.style?.getLayer("personel-layer") as? SymbolLayer

        layer?.setProperties(
            iconImage(if (useRed) "marker-red" else "marker-pink")
        )
    }

//    private fun updateCoordinates(lat: Double, lon: Double) {
//        currentLat = lat
//        currentLon = lon
//
//        binding.tvCoordinates.text  = "$lat,"
//        binding.tvCoordinates2.text = " $lon"
//    }

//    private fun applyMapType(type: MapTypeManager.MapType) {
//        val map = mapLibreMap ?: return
//
//        val currentCamera = map.cameraPosition
//        val styleUrl = MapTypeManager.getStyleUrl(type)
//
//        map.setStyle(
//            Style.Builder().fromUri(styleUrl)
//        ) { style ->
//
//            map.cameraPosition = currentCamera
//
//            geoJsonSource = GeoJsonSource(
//                "personel-source",
//                Point.fromLngLat(currentLon, currentLat)
//            )
//            style.addSource(geoJsonSource!!)
//
//            val drawableRed = ContextCompat.getDrawable(this, R.drawable.ic_location_pin)!!
//            drawableRed.setTint(Color.parseColor("#FF1744"))
//            style.addImage("marker-red", drawableToBitmap(drawableRed))
//
//            val drawablePink = ContextCompat.getDrawable(this, R.drawable.ic_location_pin)!!
//            drawablePink.setTint(Color.parseColor("#FF8A80"))
//            style.addImage("marker-pink", drawableToBitmap(drawablePink))
//
//            val symbolLayer = SymbolLayer("personel-layer", "personel-source")
//                .withProperties(
//                    iconImage("marker-red"),
//                    iconAllowOverlap(true),
//                    iconIgnorePlacement(true)
//                )
//
//            style.addLayer(symbolLayer)
//        }
//    }

    // ─── LOCATION ────────────────────────────────────────────────────────────

    private fun requestLocationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            locationPermissionRequest.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startLocationUpdates() {
        if (isLocationStarted) return

        isLocationStarted = true

        viewModel.startLocationUpdates() // tidak perlu kirim 2000 lagi
        Log.d("INIT", "START GPS")
    }

    private fun parseIntervalToMs(interval: String): Long {
        return when {
            interval.contains("minute") -> {
                val m = interval.filter { it.isDigit() }.toLongOrNull() ?: 1L
                m * 60 * 1000
            }
            interval.contains("second") -> {
                val s = interval.filter { it.isDigit() }.toLongOrNull() ?: 5L
                s * 1000
            }
            else -> 5000L
        }
    }

    private fun distance(a: LocationData, b: LocationData): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            a.lat, a.lon,
            b.lat, b.lon,
            results
        )
        return results[0]
    }

    // ─── VITAL SIGNS UI ──────────────────────────────────────────────────────

    private fun setupVitalSignsInitial() {
        updateHeartRate(0)
    }

    private fun updateHeartRate(bpm: Int) {
        pagerAdapter.heartRate = bpm
        pagerAdapter.notifyItemChanged(1)
    }

    // ─── UPDATE MQTT ─────────────────────────────────────────────────────────

    private fun updateMqttUI() {
        val config = MqttConfigManager(this).load()

        pagerAdapter.mqttHost = config.host ?: "-"
        pagerAdapter.mqttPort = config.wsPort.toString()
        pagerAdapter.interval = mqttPrefs.getString("interval", "5 seconds") ?: "5 seconds"

        pagerAdapter.notifyItemChanged(2)
    }

    // ─── CLICK LISTENERS ─────────────────────────────────────────────────────

    private fun setupClickListeners() {

//        binding.btnFullMap.setOnClickListener {
//            val intent = Intent(this, FullscreenMapActivity::class.java)
//            intent.putExtra("lat", currentLat)
//            intent.putExtra("lon", currentLon)
//            intent.putExtra("mapType", currentMapType.name)
//
//            startActivity(intent)
//        }
    }

    // ─── SWIPE REFRESH ───────────────────────────────────────────────────────

    private fun setupSwipeRefresh() {
        binding.swipeRefresh?.setOnRefreshListener {
            Log.d("SWIPE_REFRESH", "Manual refresh triggered")
            loadPersonelData()
        }
    }

    // ─── SERIAL NUMBER DIALOG ────────────────────────────────────────────────

    private fun showSerialDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_serial_req, null)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val btnLater = dialogView.findViewById<Button>(R.id.btnLater)
        val btnSetting = dialogView.findViewById<Button>(R.id.btnGoToSetting)

        btnLater.setOnClickListener {
            dialog.dismiss()
        }

        btnSetting.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        dialog.show()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }
}