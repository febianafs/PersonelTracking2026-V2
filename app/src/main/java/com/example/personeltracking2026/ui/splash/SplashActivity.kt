package com.example.personeltracking2026.ui.splash

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.personeltracking2026.R
import com.example.personeltracking2026.core.service.MqttLocationService
import com.example.personeltracking2026.core.session.SessionManager
import com.example.personeltracking2026.data.repository.AuthRepository
import com.example.personeltracking2026.data.repository.Result
import com.example.personeltracking2026.databinding.ActivitySplashBinding
import com.example.personeltracking2026.ui.bodycam.BodycamActivity
import com.example.personeltracking2026.ui.login.LoginActivity
import com.example.personeltracking2026.ui.main.MainActivity
import com.example.personeltracking2026.ui.personel.PersonelActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.provider.Settings
import com.example.personeltracking2026.App
import com.example.personeltracking2026.core.navigation.LastScreen
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.PackageInfoCompat
import com.example.personeltracking2026.data.model.UpdateApkResponse
import com.example.personeltracking2026.core.network.RetrofitClient
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Environment
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.example.personeltracking2026.core.network.UpdaterRetrofitClient
import com.example.personeltracking2026.BuildConfig

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var loadingDialog: AlertDialog
    private val authRepository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager(this)

        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.splash_fade_in)
        binding.imgLogo.startAnimation(fadeIn)

        binding.tvAppVersion.text =
            "ver ${BuildConfig.VERSION_NAME}"

        lifecycleScope.launch {
            val minDelay = launch { delay(2000) }

            // Start service setelah Activity benar-benar visible
            // delay singkat memastikan Activity sudah resumed
            delay(500)

            if (sessionManager.isLoggedIn()) {
                (application as App).mqttManager.connect()
                MqttLocationService.startService(this@SplashActivity)
            }

            val hasUpdate = checkForUpdate()
            minDelay.join()
            if (!hasUpdate) {
                continueToApp()
            }
        }
    }

    private suspend fun validateToken(): Class<*> {
        val token = sessionManager.getToken()
            ?: return LoginActivity::class.java

        return when (val result = authRepository.checkToken(token)) {
            is Result.Success -> getDestinationFromSession()

            is Result.Error -> {
                when (result.message) {
                    "TOKEN_EXPIRED",
                    "TOKEN_FORBIDDEN" -> LoginActivity::class.java

                    "NETWORK_ERROR" -> getDestinationFromSession()

                    else -> getDestinationFromSession()
                }
            }

            is Result.Loading -> getDestinationFromSession()
        }
    }

    private fun getDestinationFromSession(): Class<*> {
        val lastScreen = sessionManager.getLastScreen()

        return if (lastScreen != null) {
            when (lastScreen) {
                LastScreen.PERSONEL -> PersonelActivity::class.java
                LastScreen.BODYCAM -> BodycamActivity::class.java
            }
        } else {
            when (sessionManager.getRole()) {
                SessionManager.ROLE_PERSONEL -> PersonelActivity::class.java
                SessionManager.ROLE_BODYCAM -> BodycamActivity::class.java
                else -> MainActivity::class.java
            }
        }
    }

    private suspend fun checkForUpdate(): Boolean {
        return try {
            val response =
                UpdaterRetrofitClient.api.getLatestVersion()

            if (response.isSuccessful) {
                val data = response.body()
                data?.let {
                    val packageInfo =
                        packageManager.getPackageInfo(packageName, 0)
                    val currentVersionCode =
                        PackageInfoCompat
                            .getLongVersionCode(packageInfo)
                            .toInt()

                    if (it.versionCode > currentVersionCode) {
                        showUpdateDialog(it)
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun showUpdateDialog(data: UpdateApkResponse) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage(
                    """
                Version: ${data.versionName}
                
                ${data.changelog}
                """.trimIndent()
                )
                .setCancelable(false)
                .setPositiveButton("Update") { _, _ ->
                    downloadAndInstallAPK(data.apkUrl)
                }
                .setNegativeButton("Later") { _, _ ->
                    continueToApp()
                }
                .show()
        }
    }

    private fun continueToApp() {
        lifecycleScope.launch {
            val destination = if (sessionManager.isLoggedIn()) {
                validateToken()
            } else {
                LoginActivity::class.java
            }
            binding.root.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    val intent =
                        Intent(this@SplashActivity, destination)
                    intent.flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    finish()
                }.start()
        }
    }

    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun downloadAndInstallAPK(apkUrl: String) {
        val request =
            DownloadManager.Request(Uri.parse(apkUrl))

        request.setTitle("Downloading Update")
        request.setDescription("Please wait...")
        request.setMimeType(
            "application/vnd.android.package-archive"
        )
        request.setNotificationVisibility(
            DownloadManager.Request
                .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )
        request.setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            "update.apk"
        )

        loadingDialog =
            AlertDialog.Builder(this)
                .setTitle("Updating")
                .setMessage("Downloading latest version...")
                .setCancelable(false)
                .create()
        loadingDialog.show()
        val downloadManager =
            getSystemService(Context.DOWNLOAD_SERVICE)
                    as DownloadManager
        val downloadId =
            downloadManager.enqueue(request)

        lifecycleScope.launch {
            var downloading = true
            while (downloading) {
                val query =
                    DownloadManager.Query()
                        .setFilterById(downloadId)
                val cursor =
                    downloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val status =
                        cursor.getInt(
                            cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_STATUS
                            )
                        )

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            downloading = false
                            loadingDialog.dismiss()
                            val uri =
                                downloadManager
                                    .getUriForDownloadedFile(
                                        downloadId
                                    )
                            installAPK(uri)
                        }

                        DownloadManager.STATUS_FAILED -> {
                            downloading = false
                            loadingDialog.dismiss()
                            Toast.makeText(
                                this@SplashActivity,
                                "Download failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                cursor.close()
                delay(1000)
            }
        }
    }

    private fun installAPK(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(
            uri,
            "application/vnd.android.package-archive"
        )
        intent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION

        startActivity(intent)
    }
}