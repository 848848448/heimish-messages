package com.heimish.messages

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper

/**
 * "Send location": the phone's freshest position. The last known position is often missing (nothing asked for a fix
 * lately) or hours old, which showed "Location unavailable". Now: a recent last-known fix is used at once; otherwise a
 * fresh fix is requested (up to [timeoutMs]), falling back to the newest last-known one.
 */
object LocationFix {
    private const val FRESH_MS = 2 * 60_000L

    @SuppressLint("MissingPermission")
    fun get(ctx: Context, timeoutMs: Long = 15_000L, onResult: (Location?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run { onResult(null); return }
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val known = enabled.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.maxByOrNull { it.time }
        if (known != null && System.currentTimeMillis() - known.time < FRESH_MS) { onResult(known); return }
        val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in enabled }
            ?: run { onResult(known); return }
        var done = false
        val signal = CancellationSignal()
        var listener: LocationListener? = null
        fun finish(l: Location?) {
            if (done) return
            done = true
            runCatching { signal.cancel() }
            listener?.let { runCatching { lm.removeUpdates(it) } }
            onResult(l ?: known)
        }
        main.postDelayed({ finish(null) }, timeoutMs)
        runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, signal, { r -> main.post(r) }) { l -> main.post { finish(l) } }
            } else {
                val ll = object : LocationListener {
                    override fun onLocationChanged(l: Location) { main.post { finish(l) } }
                    @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }
                listener = ll
                @Suppress("DEPRECATION") lm.requestSingleUpdate(provider, ll, Looper.getMainLooper())
            }
        }.onFailure { finish(null) }
    }
}
