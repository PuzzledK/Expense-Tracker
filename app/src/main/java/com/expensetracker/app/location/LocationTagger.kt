package com.expensetracker.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.expensetracker.app.data.ExpenseDb
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * City-level location for tagging expenses. Uses only ACCESS_COARSE_LOCATION and the
 * platform LocationManager + Geocoder (no Google Play Services). Everything here blocks,
 * so it runs on its own background thread and never delays saving an expense.
 */
object LocationTagger {

    data class Fix(val city: String?, val lat: Double, val lon: Double)

    private val worker = Executors.newSingleThreadExecutor()
    private const val FRESH_MS = 20 * 60 * 1000L        // reuse a fix up to 20 min old
    private const val STALE_OK_MS = 6 * 60 * 60 * 1000L // fall back to one up to 6 h old
    private const val TIMEOUT_S = 12L

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(ctx: Context) =
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("locationTagging", false) && hasPermission(ctx)

    /** Looks up the current city and writes it onto [ids] in the background. */
    fun tagAsync(ctx: Context, ids: List<Long>, onDone: () -> Unit = {}) {
        if (ids.isEmpty() || !isEnabled(ctx)) return
        val app = ctx.applicationContext
        worker.execute {
            val fix = currentFix(app) ?: return@execute
            ExpenseDb(app).use { it.setPlace(ids, fix.city, fix.lat, fix.lon) }
            onDone()
        }
    }

    /** One-off "use my current location" for the place picker; [onResult] runs on a background thread. */
    fun currentCityAsync(ctx: Context, onResult: (Fix?) -> Unit) {
        val app = ctx.applicationContext
        worker.execute { onResult(if (hasPermission(app)) currentFix(app) else null) }
    }

    /** Fills in cities for rows saved with coordinates while offline. */
    fun backfillAsync(ctx: Context, onChanged: () -> Unit) {
        val app = ctx.applicationContext
        worker.execute {
            ExpenseDb(app).use { db ->
                val missing = db.all().filter { it.place == null && it.lat != null && it.lon != null }
                if (missing.isEmpty()) return@execute
                var changed = false
                missing.groupBy { "%.2f,%.2f".format(Locale.ROOT, it.lat, it.lon) }.values.forEach { group ->
                    val city = cityFor(app, group[0].lat!!, group[0].lon!!) ?: return@forEach
                    db.setPlace(group.map { it.id }, city)
                    changed = true
                }
                if (changed) onChanged()
            }
        }
    }

    // ---------------- internals (blocking) ----------------

    private fun currentFix(ctx: Context): Fix? {
        val loc = locate(ctx) ?: return null
        return Fix(cityFor(ctx, loc.latitude, loc.longitude), loc.latitude, loc.longitude)
    }

    @Suppress("MissingPermission")
    private fun locate(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }.filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }

        val now = System.currentTimeMillis()
        val lastKnown = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null && now - lastKnown.time < FRESH_MS) return lastKnown

        for (p in providers.filter { it != LocationManager.PASSIVE_PROVIDER }) {
            requestOnce(lm, p)?.let { return it }
        }
        return lastKnown?.takeIf { now - it.time < STALE_OK_MS }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun requestOnce(lm: LocationManager, provider: String): Location? {
        val latch = CountDownLatch(1)
        var result: Location? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, null, callbackExecutor) { l ->
                    result = l
                    latch.countDown()
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) {
                        result = l
                        latch.countDown()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(p: String) = Unit
                    override fun onProviderDisabled(p: String) { latch.countDown() }
                }
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                if (!latch.await(TIMEOUT_S, TimeUnit.SECONDS)) lm.removeUpdates(listener)
                return result
            }
        } catch (_: SecurityException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        latch.await(TIMEOUT_S, TimeUnit.SECONDS)
        return result
    }

    /** Callbacks must not run on [worker] itself (it is blocked waiting), so use a fresh thread. */
    private val callbackExecutor = java.util.concurrent.Executor { r -> Thread(r).start() }

    @Suppress("DEPRECATION")
    private fun cityFor(ctx: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val a = runCatching { Geocoder(ctx, Locale.ENGLISH).getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
            ?: return null
        return (a.locality ?: a.subAdminArea ?: a.adminArea)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
