package com.stampshot.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.stampshot.app.stamp.LocationStamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Best-effort location + reverse-geocode snapshot for stamping.
 * Uses the platform LocationManager (no Play Services dependency).
 * Everything is optional — returns null pieces when permission/GPS is missing.
 *
 * Snapshots are cached briefly: the camera screen prefetches in the background
 * so taking a photo never waits on GPS — a fresh fix is only awaited when the
 * cache is stale.
 */
object LocationStamper {

    private const val CACHE_TTL_MS = 60_000L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var cached: LocationStamp? = null
    @Volatile private var cachedAtMs: Long = 0L

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    /** Warms the cache in the background so the next [snapshot] is instant. */
    fun prefetch(context: Context) {
        if (!hasPermission(context)) return
        val appContext = context.applicationContext
        scope.launch { fetch(appContext, timeoutMs = 5000)?.also { store(it) } }
    }

    /**
     * Fresh snapshot: returns the cached fix while it's younger than the TTL,
     * otherwise awaits a fresh fix up to [timeoutMs]. Null if unavailable.
     */
    suspend fun snapshot(context: Context, timeoutMs: Long = 1500): LocationStamp? =
        withContext(Dispatchers.IO) {
            if (!hasPermission(context)) return@withContext null
            cached?.takeIf { System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS }
                ?.let { return@withContext it }
            fetch(context, timeoutMs)?.also { store(it) }
        }

    private fun store(stamp: LocationStamp) {
        cached = stamp
        cachedAtMs = System.currentTimeMillis()
    }

    private suspend fun fetch(context: Context, timeoutMs: Long): LocationStamp? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = withTimeoutOrNull(timeoutMs) { freshFix(lm) } ?: lastKnown(lm)
            ?: return null
        val (address, city) = withTimeoutOrNull(4000) {
            geocode(context, location.latitude, location.longitude)
        } ?: (null to null)
        return LocationStamp(location, address, city)
    }

    /** Cheap last-known fix for the live preview — never blocks. */
    fun lastKnown(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lastKnown(lm)
    }

    private fun lastKnown(lm: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return providers.mapNotNull {
            runCatching { lm.getLastKnownLocation(it) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private suspend fun freshFix(lm: LocationManager): Location? {
        val provider = when {
            runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
                LocationManager.GPS_PROVIDER
            runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                LocationManager.NETWORK_PROVIDER
            else -> return lastKnown(lm)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            suspendCancellableCoroutine { cont ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    lm.getCurrentLocation(provider, null, executor) { loc ->
                        cont.resume(loc)
                        executor.shutdown()
                    }
                } catch (e: SecurityException) {
                    cont.resume(null)
                } catch (e: IllegalArgumentException) {
                    cont.resume(null)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            suspendCancellableCoroutine { cont ->
                try {
                    lm.requestSingleUpdate(provider, { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }, null)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private suspend fun geocode(context: Context, lat: Double, lon: Double): Pair<String?, String?> {
        if (!Geocoder.isPresent()) return null to null
        return runCatching {
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<Address>> { cont ->
                    Geocoder(context).getFromLocation(lat, lon, 1) { res ->
                        if (cont.isActive) cont.resume(res)
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    Geocoder(context).getFromLocation(lat, lon, 1) ?: emptyList()
                }
            }
            val a = addresses.firstOrNull() ?: return@runCatching (null to null)
            formatAddress(a) to formatCity(a)
        }.getOrDefault(null to null)
    }

    private fun formatAddress(a: Address): String? {
        val parts = listOfNotNull(
            a.thoroughfare?.let { listOfNotNull(a.subThoroughfare, it).joinToString(" ").ifBlank { null } },
            a.subLocality ?: a.locality,
            a.adminArea,
            a.countryName,
        )
        return parts.joinToString(", ").ifBlank { a.getAddressLine(0) }
    }

    private fun formatCity(a: Address): String? =
        listOfNotNull(a.locality ?: a.subAdminArea ?: a.adminArea, a.countryName)
            .joinToString(", ")
            .ifBlank { null }
}
