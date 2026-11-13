package com.cityguide

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Поточне місцезнаходження без Google Play Services: спершу свіжий фікс від мережі/GPS,
 * якщо не вийшло, то останнє відоме положення. Дозвіл має бути виданий до виклику.
 */
@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context): Location? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    for (provider in providers) {
        val fix = withTimeoutOrNull(8_000) { requestFix(context, lm, provider) }
        if (fix != null) return fix
    }
    return try {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (e: SecurityException) {
        null
    }
}

@SuppressLint("MissingPermission")
private suspend fun requestFix(context: Context, lm: LocationManager, provider: String): Location? =
    suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        try {
            LocationManagerCompat.getCurrentLocation(
                lm, provider, signal, ContextCompat.getMainExecutor(context)
            ) { location ->
                if (cont.isActive) cont.resume(location)
            }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        }
    }
