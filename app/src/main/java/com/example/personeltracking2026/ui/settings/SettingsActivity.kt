package com.example.personeltracking2026.ui.settings

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.content.Intent
import com.example.personeltracking2026.R
import com.example.personeltracking2026.core.base.BaseActivity
import com.example.personeltracking2026.core.mqtt.MqttConfig
import com.example.personeltracking2026.core.mqtt.MqttConfigManager
import com.example.personeltracking2026.core.mqtt.MqttManager
import com.example.personeltracking2026.core.mqtt.MqttTopicConfig
import com.example.personeltracking2026.core.mqtt.MqttTopicManager
import com.example.personeltracking2026.core.utils.Constants
import com.example.personeltracking2026.databinding.ActivitySettingsBinding
import com.example.personeltracking2026.utils.DeviceIdentityManager

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var testMqttManager: MqttManager? = null

    // FIX 2: Simpan callback lama agar bisa di-restore saat Activity destroy
    private var prevOnConnected: (() -> Unit)? = null
    private var prevOnDisconnected: (() -> Unit)? = null

    private lateinit var deviceManager: DeviceIdentityManager

    private val intervalOptions = listOf(
        "1 second", "2 seconds", "5 seconds", "10 seconds",
        "15 seconds", "30 seconds", "1 minute", "2 minutes",
        "5 minutes", "10 minutes"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceManager = DeviceIdentityManager(this)

        setupWindow()
        setupToolbar()
        setupIntervalDropdown()
        loadSettings()
        setupButtons()
        observeMainMqttStatus()

        binding.radioGroupConnectionType?.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioTcp -> {
                    binding.etTcp?.isEnabled = true
                    binding.etWs?.isEnabled = false
                }
                R.id.radioWebSocket -> {
                    binding.etTcp?.isEnabled = false
                    binding.etWs?.isEnabled = true
                }
            }
        }
    }

    // FIX 2: Restore callback lama ke MqttManager utama saat Activity di-destroy
    override fun onDestroy() {
        super.onDestroy()
        val mainMqtt = (application as? com.example.personeltracking2026.App)?.mqttManager
        mainMqtt?.onConnected = prevOnConnected
        mainMqtt?.onDisconnected = prevOnDisconnected

        testMqttManager?.disconnect()
        testMqttManager = null
    }

    // ─── REALTIME STATUS dari MqttManager utama ───────────────────────────

    // FIX 2: Simpan callback lama sebelum di-replace, agar tidak hilang
    private fun observeMainMqttStatus() {
        val mainMqtt = (application as? com.example.personeltracking2026.App)?.mqttManager
            ?: return

        // Simpan callback lama sebelum di-replace
        prevOnConnected = mainMqtt.onConnected
        prevOnDisconnected = mainMqtt.onDisconnected

        // Set status awal saat activity dibuka
        if (mainMqtt.isConnected()) {
            updateStatus("Connected", "#69F0AE")
        } else {
            updateStatus("Disconnected", "#9E9E9E")
        }

        // Listen perubahan status realtime
        mainMqtt.onConnected = {
            runOnUiThread {
                if (!isDestroyed && !isFinishing) updateStatus("Connected", "#69F0AE")
            }
        }
        mainMqtt.onDisconnected = {
            runOnUiThread {
                if (!isDestroyed && !isFinishing) updateStatus("Disconnected", "#9E9E9E")
            }
        }
    }

    // ─── UI SETUP ────────────────────────────────────────────────────────────

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupIntervalDropdown() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            intervalOptions
        )
        binding.actInterval.setAdapter(adapter)
        binding.actInterval.setOnItemClickListener { _, _, _, _ ->
            val interval = binding.actInterval.text.toString()
            getSharedPreferences(Constants.PREFS_MQTT_SETTINGS, MODE_PRIVATE).edit {
                putString(Constants.KEY_INTERVAL, interval)
            }
            sendBroadcast(
                Intent(Constants.ACTION_INTERVAL_CHANGED).apply {
                    putExtra(Constants.EXTRA_INTERVAL_TEXT, interval)
                }
            )
            showSavedIndicator()
        }
    }

    // ─── LOAD CONFIG ─────────────────────────────────────────────────────────

    private fun loadSettings() {
        val config = MqttConfigManager(this).load()
        binding.etServer?.setText(config.host)
        binding.etTcp?.setText(config.tcpPort.toString())
        binding.etWs?.setText(config.wsPort.toString())
        binding.etUsername?.setText(config.username)
        binding.etPassword?.setText(config.password)

        if (config.useWebSocket) {
            binding.radioWebSocket?.isChecked = true
            binding.etTcp?.isEnabled = false
            binding.etWs?.isEnabled = true
        } else {
            binding.radioTcp?.isChecked = true
            binding.etTcp?.isEnabled = true
            binding.etWs?.isEnabled = false
        }

        val prefs = getSharedPreferences(Constants.PREFS_MQTT_SETTINGS, MODE_PRIVATE)
        val savedInterval = prefs.getString(
            Constants.KEY_INTERVAL,
            Constants.DEFAULT_INTERVAL_TEXT
        ) ?: Constants.DEFAULT_INTERVAL_TEXT
        binding.actInterval.setText(savedInterval, false)

        val serial = deviceManager.getValidSerial()
        binding.etSerialNumber?.setText(serial ?: "")
        binding.etSerialNumber?.isEnabled = true

        val topicConfig = MqttTopicManager(this).load()
        binding.etTopicPersonelData?.setText(topicConfig.personelDataTopic)
        binding.etTopicPersonelSos?.setText(topicConfig.personelSosTopic)
        binding.etTopicBodycamData?.setText(topicConfig.bodycamDataTopic)
        binding.etTopicBodycamSos?.setText(topicConfig.bodycamSosTopic)
    }

    // ─── BUTTONS ─────────────────────────────────────────────────────────────

    private fun setupButtons() {

        // ─── SAVE SERIAL ─────────────────────────────
        binding.btnSaveSerial!!.setOnClickListener {

            val inputSerial = binding.etSerialNumber!!.text.toString().trim()

            if (inputSerial.isEmpty()) {
                binding.etSerialNumber!!.error = "Serial number is required"
                return@setOnClickListener
            }

            val existing = deviceManager.getValidSerial()

            if (inputSerial == existing) {
                showSavedIndicator()
                return@setOnClickListener
            }

            if (existing == null) {
                deviceManager.saveSerialIfEmpty(inputSerial)
                showSavedIndicator()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Update Serial")
                    .setMessage("Serial number already exists. Do you want to replace it?")
                    .setPositiveButton("Yes") { _, _ ->
                        deviceManager.forceUpdateSerial(inputSerial)
                        showSavedIndicator()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        }

        // ─── SAVE MQTT CONFIG ───────────────────────
        binding.btnSaveConnection.setOnClickListener {

            val config = buildConfigFromInput()

            val topicConfig = MqttTopicConfig(
                personelDataTopic = binding.etTopicPersonelData?.text.toString().trim(),
                personelSosTopic  = binding.etTopicPersonelSos?.text.toString().trim(),
                bodycamDataTopic  = binding.etTopicBodycamData?.text.toString().trim(),
                bodycamSosTopic   = binding.etTopicBodycamSos?.text.toString().trim()
            )

            MqttConfigManager(this).save(config)
            MqttTopicManager(this).save(topicConfig)

            val app = application as? com.example.personeltracking2026.App
            app?.mqttManager?.reconnectWithNewSettings()

            showSavedIndicator()
        }

        // ─── TEST CONNECTION ────────────────────────
        // FIX 3: Pakai connectWithConfig() agar test pakai config dari input field,
        // bukan config lama yang tersimpan di SharedPrefs
        binding.btnTestConnection.setOnClickListener {

            val config = buildConfigFromInput()
            updateTestButtonState(loading = true)

            testMqttManager?.disconnect()

            testMqttManager = MqttManager(this).apply {

                onConnected = {
                    runOnUiThread {
                        if (!isDestroyed && !isFinishing) {
                            updateTestButtonState(loading = false)
                            showConnectionSuccessDialog(config)
                        }
                    }
                }

                onDisconnected = {
                    runOnUiThread {
                        if (!isDestroyed && !isFinishing) {
                            updateTestButtonState(loading = false)
                            showConnectionFailedDialog()
                        }
                    }
                }
            }

            // FIX 3: Gunakan connectWithConfig, bukan connect()
            testMqttManager?.connectWithConfig(config)
        }
    }

    private fun updateTestButtonState(loading: Boolean) {
        binding.btnTestConnection?.apply {
            isEnabled = !loading
            text = if (loading) "Testing..." else "Test"
        }
    }

    private fun buildConfigFromInput(): MqttConfig {
        return MqttConfig(
            host         = binding.etServer?.text.toString().trim(),
            tcpPort      = binding.etTcp?.text.toString().toIntOrNull() ?: 1883,
            wsPort       = binding.etWs?.text.toString().toIntOrNull() ?: 9001,
            username     = binding.etUsername?.text.toString().trim(),
            password     = binding.etPassword?.text.toString(),
            useWebSocket = binding.radioWebSocket?.isChecked ?: false
        )
    }

    // ─── DIALOG ──────────────────────────────────────────────────────────────

    private fun showConnectionSuccessDialog(config: MqttConfig) {
        val connType = if (config.useWebSocket) "WebSocket" else "TCP"
        val port     = if (config.useWebSocket) config.wsPort else config.tcpPort
        val maskedPass = if (config.password.isNotBlank())
            "*".repeat(config.password.length) else "(none)"

        val message = """
            ✓ Successfully connected to broker
            
            Connection Type : $connType
            Server Host     : ${config.host}
            Port            : $port
            Username        : ${config.username.ifBlank { "(none)" }}
            Password        : $maskedPass
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Connection Info")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
                testMqttManager?.disconnect()
                testMqttManager = null
            }
            .setCancelable(false)
            .show()
    }

    private fun showConnectionFailedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Connection Failed")
            .setMessage("Could not connect to broker.\nPlease check your settings and try again.")
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    // ─── STATUS UI ───────────────────────────────────────────────────────────

    private fun updateStatus(text: String, colorHex: String) {
        if (isDestroyed || isFinishing) return
        val color = colorHex.toColorInt()
        binding.tvConnectionStatus?.text = text
        binding.tvConnectionStatus?.setTextColor(color)
        binding.dotConnection?.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun showSavedIndicator() {
        val tvSaved = binding.tvSaved
        tvSaved?.visibility = View.VISIBLE
        tvSaved?.alpha = 0f
        tvSaved?.animate()
            ?.alpha(1f)
            ?.setDuration(200)
            ?.withEndAction {
                tvSaved?.postDelayed({
                    tvSaved?.animate()
                        ?.alpha(0f)
                        ?.setDuration(400)
                        ?.withEndAction {
                            tvSaved?.visibility = View.GONE
                        }?.start()
                }, 1500)
            }?.start()
    }
}