package com.example.personeltracking2026.data.repository

import com.example.personeltracking2026.core.network.RetrofitClient

class AuthRepository {
    private val api = RetrofitClient.instance

    suspend fun checkToken(token: String): Result<Boolean> {
        return try {
            val response = api.checkToken("Bearer $token")
            when (response.code()) {
                200 -> Result.Success(true)
                401 -> Result.Error("TOKEN_EXPIRED")
                403 -> Result.Error("TOKEN_FORBIDDEN")
                else -> Result.Error("TOKEN_CHECK_FAILED_${response.code()}")
            }
        } catch (e: Exception) {
            Result.Error("NETWORK_ERROR")
        }
    }
}