package com.example.personeltracking2026.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.personeltracking2026.BuildConfig
import com.example.personeltracking2026.R
import com.example.personeltracking2026.core.base.BaseActivity
import com.example.personeltracking2026.data.repository.AboutRepository
import com.example.personeltracking2026.data.repository.Result
import com.example.personeltracking2026.databinding.ActivityAboutBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import com.example.personeltracking2026.core.session.SessionManager

class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    private val viewModel: AboutViewModel by viewModels {
        AboutViewModel.Factory(AboutRepository())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.btnBack.setOnClickListener {
            finish()
        }

        observeAboutState()
        setupSwipeRefresh()

        val sessionManager = SessionManager(this)

        val token = sessionManager.getToken()

        if (token != null) {

            viewModel.fetchAboutUs(token)

        } else {

            Snackbar.make(
                binding.root,
                "Session expired. Please login again.",
                Snackbar.LENGTH_LONG
            ).show()

            finish()
        }
    }

    private fun setupSwipeRefresh() {

        binding.swipeRefresh.setOnRefreshListener {

            val token = SessionManager(this).getToken()

            if (token != null) {
                viewModel.fetchAboutUs(token)
            } else {

                binding.swipeRefresh.isRefreshing = false

                Snackbar.make(
                    binding.root,
                    "Session expired. Please login again.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun playStaggeredAnimation() {
        val anim = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in)
        val delay = 100L

        val views = listOf(
            binding.cardBranding,
            binding.cardInfo,
//            binding.cardLegal,
//            binding.btnPrivacyPolicy
        )

        views.forEachIndexed { index, view ->
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.postDelayed({
                view.alpha = 1f
                view.startAnimation(anim)
            }, index * delay)
        }
    }

    private fun observeAboutState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.aboutState.collect { state ->
                    when (state) {
                        is Result.Loading -> {
                            if (!binding.swipeRefresh.isRefreshing) {
                                binding.progressBar.visibility = View.VISIBLE
                                binding.scrollView.visibility = View.GONE
                            }
                        }
                        is Result.Success -> {
                            binding.swipeRefresh.isRefreshing = false
                            binding.progressBar.visibility = View.GONE
                            binding.scrollView.visibility = View.VISIBLE

                            val data = state.data.data
                            binding.tvAppName.text = data?.app_name ?: "Personel Tracking"
                            binding.tvAppCode.text = data?.app_code ?: "PT-2026"
                            binding.tvDev.text = data?.dev ?: "RTI Dev"
                            binding.tvCompany.text = data?.company_name ?: "PT.DHARMAPALA AGUNG SEJAHTRA"
                            binding.tvCopyright.text = data?.copyright_text ?: "©2026"
                            binding.tvAppVersion.text = BuildConfig.VERSION_NAME
//                            binding.tvLegalNotice.text = data?.legal_notice ?: "All rights reserved"

                            val privacyUrl = data?.privacy_policy_url
//                            if (!privacyUrl.isNullOrEmpty()) {
//                                binding.btnPrivacyPolicy.setOnClickListener {
//                                    startActivity(
//                                        Intent(Intent.ACTION_VIEW, Uri.parse(privacyUrl))
//                                    )
//                                }
//                            } else {
//                                binding.btnPrivacyPolicy.visibility = View.GONE
//                            }

                            // Jalankan animasi setelah data masuk
                            playStaggeredAnimation()
                        }
                        is Result.Error -> {
                            binding.swipeRefresh.isRefreshing = false
                            binding.progressBar.visibility = View.GONE
                            Snackbar.make(
                                binding.root,
                                state.message,
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                        null -> {
                            binding.progressBar.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }
}