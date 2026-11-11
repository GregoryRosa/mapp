package br.com.mapp.maps

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.annotation.RequiresApi
import br.com.mapp.core.geo.Geo
import br.com.mapp.core.model.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.cos

/**
 * Endereço → coordenada usando o Geocoder do sistema (sem chave de API).
 * Cache LRU em memória; com posição atual, a busca é enviesada para ~30 km em volta.
 * Nunca lança exceção: retorna null em qualquer falha.
 */
class GeocodingRepository private constructor(context: Context) {

    private val geocoder = Geocoder(context.applicationContext, PT_BR)
    private val cache = LruCache<String, GeoPoint>(CACHE_SIZE)

    suspend fun geocode(address: String?, near: GeoPoint? = null): GeoPoint? {
        val query = address?.trim()?.replace(WHITESPACE, " ")?.takeIf { it.length >= 3 } ?: return null
        val key = query.lowercase(PT_BR)
        cache.get(key)?.let { return it }
        if (!Geocoder.isPresent()) return null

        val found = try {
            lookup(query, near) ?: if (near != null) {
                // Sem resultado na região: tenta sem viés, mas descarta resultados muito longe.
                lookup(query, null)?.takeIf { Geo.haversineKm(it, near) <= MAX_UNBIASED_DISTANCE_KM }
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao geocodificar \"$query\"", e)
            null
        }
        if (found != null) cache.put(key, found)
        return found
    }

    private suspend fun lookup(query: String, near: GeoPoint?): GeoPoint? {
        val box = near?.let { boundsAround(it, BIAS_RADIUS_KM) }
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            asyncLookup(query, box)
        } else {
            withContext(Dispatchers.IO) { blockingLookup(query, box) }
        }
        val points = addresses
            .filter { it.hasLatitude() && it.hasLongitude() }
            .map { GeoPoint(it.latitude, it.longitude) }
        return if (near != null) points.minByOrNull { Geo.haversineKm(it, near) } else points.firstOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun asyncLookup(query: String, box: DoubleArray?): List<Address> =
        suspendCancellableCoroutine { cont ->
            val listener = object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (cont.isActive) cont.resume(addresses.toList())
                }

                override fun onError(errorMessage: String?) {
                    Log.w(TAG, "Geocoder: $errorMessage")
                    if (cont.isActive) cont.resume(emptyList())
                }
            }
            if (box == null) {
                geocoder.getFromLocationName(query, MAX_RESULTS, listener)
            } else {
                geocoder.getFromLocationName(query, MAX_RESULTS, box[0], box[1], box[2], box[3], listener)
            }
        }

    @Suppress("DEPRECATION")
    private fun blockingLookup(query: String, box: DoubleArray?): List<Address> =
        if (box == null) {
            geocoder.getFromLocationName(query, MAX_RESULTS)
        } else {
            geocoder.getFromLocationName(query, MAX_RESULTS, box[0], box[1], box[2], box[3])
        } ?: emptyList()

    /** [lowerLeftLat, lowerLeftLon, upperRightLat, upperRightLon] em volta de [center]. */
    private fun boundsAround(center: GeoPoint, radiusKm: Double): DoubleArray {
        val dLat = radiusKm / KM_PER_DEGREE
        val dLon = radiusKm / (KM_PER_DEGREE * cos(Math.toRadians(center.lat)).coerceAtLeast(0.01))
        return doubleArrayOf(
            (center.lat - dLat).coerceIn(-90.0, 90.0),
            (center.lon - dLon).coerceIn(-180.0, 180.0),
            (center.lat + dLat).coerceIn(-90.0, 90.0),
            (center.lon + dLon).coerceIn(-180.0, 180.0),
        )
    }

    companion object {
        private const val TAG = "MApp/Geocoding"
        private const val CACHE_SIZE = 256
        private const val MAX_RESULTS = 5
        private const val BIAS_RADIUS_KM = 30.0
        private const val MAX_UNBIASED_DISTANCE_KM = 150.0
        private const val KM_PER_DEGREE = 111.32
        private val PT_BR: Locale = Locale.forLanguageTag("pt-BR")
        private val WHITESPACE = Regex("\\s+")

        @Volatile
        private var instance: GeocodingRepository? = null

        fun get(context: Context): GeocodingRepository =
            instance ?: synchronized(this) {
                instance ?: GeocodingRepository(context).also { instance = it }
            }
    }
}
