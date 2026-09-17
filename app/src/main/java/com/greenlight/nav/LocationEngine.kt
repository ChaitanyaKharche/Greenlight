package com.greenlight.nav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.greenlight.core.LatLon
import com.greenlight.model.Fix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 1 Hz fused location as a Flow of [Fix].
 *
 * 1 Hz is the right rate here: GLOSA advice that updates faster is unreadable at the wheel, and
 * slower loses the resolution you need in the last hundred metres.
 */
class LocationEngine(private val context: Context) {

    @SuppressLint("MissingPermission") // The caller gates this on the runtime permission.
    fun fixes(intervalMs: Long = 1000L): Flow<Fix> = callbackFlow {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toFix()) }
            }
        }

        client.requestLocationUpdates(request, callback, context.mainLooper)
        awaitClose { client.removeLocationUpdates(callback) }
    }
}

fun Location.toFix() = Fix(
    position = LatLon(latitude, longitude),
    speedMps = if (hasSpeed()) speed.toDouble() else 0.0,
    bearingDeg = if (hasBearing()) bearing.toDouble() else 0.0,
    epochSec = time / 1000.0,
    accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else 25.0,
    hasBearing = hasBearing(),
)
