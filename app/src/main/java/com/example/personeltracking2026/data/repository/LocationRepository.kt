package com.example.personeltracking2026.data.repository

import com.example.personeltracking2026.data.model.LocationData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.Result

// Ganti LocationRepository jadi passive — terima data dari luar
class LocationRepository {

    private val _locationFlow = MutableSharedFlow<Result<LocationData>>(
        replay = 1,
        extraBufferCapacity = 10
    )
    val locationFlow: SharedFlow<Result<LocationData>> = _locationFlow.asSharedFlow()

    suspend fun emit(data: LocationData) {
        _locationFlow.emit(Result.success(data))
    }

    suspend fun emitError(message: String) {
        _locationFlow.emit(Result.failure(Exception(message)))
    }
}