package com.example.personeltracking2026.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.personeltracking2026.core.session.SessionManager
import com.example.personeltracking2026.ui.login.LoginActivity
import com.example.personeltracking2026.ui.personel.PersonelActivity

class MainActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionManager = SessionManager(this)

        if (!sessionManager.isLoggedIn()) {
            goToLogin()
            return
        }

        sessionManager.saveRole(SessionManager.ROLE_PERSONEL)
        goToPersonel()
    }

    private fun goToPersonel() {
        val intent = Intent(this, PersonelActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun goToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}