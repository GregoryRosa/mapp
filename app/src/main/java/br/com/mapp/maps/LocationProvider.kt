package br.com.mapp.maps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import br.com.mapp.core.model.GeoPoint
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Posição atual via Fused Location. Sem permissão, tudo retorna null / Flow vazio. */
class LocationProvider private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    /** Última posição conhecida por este app (atualizada por [current] e [updates]). */
    @Volatile
    var lastKnown: GeoPoint? = null
        private set

    fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(perm: String) =
        ContextCompat.checkSelfPermission(appContext, perm) == PackageManager.PERMISSION_GRANTED

    /** Posição atual (balanceada); cai para a última localização do sistema se não vier a tempo. */
    @SuppressLint("MissingPermission")
    suspend fun current(timeoutMs: Long = CURRENT_TIMEOUT_MS): GeoPoint? {
        if (!hasPermission()) return null
        return try {
            val cts = CancellationTokenSource()
            val fresh = withTimeoutOrNull(timeoutMs) {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token).awaitOrNull(cts)
            }
            if (fresh == null) cts.cancel()
            val loc = fresh ?: client.lastLocation.awaitOrNull()
            loc?.toGeoPoint()?.also { lastKnown = it } ?: lastKnown
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao obter localização", e)
            lastKnown
        }
    }

    /** Atualizações periódicas enquanto houver coletor (ex.: overlay ativo). */
    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long = UPDATE_INTERVAL_MS): Flow<GeoPoint> = callbackFlow {
        if (!hasPermission()) {
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 3)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.toGeoPoint()?.let {
                    lastKnown = it
                    trySend(it)
                }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "Permissão de localização revogada", e)
            close()
            return@callbackFlow
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun Location.toGeoPoint() = GeoPoint(latitude, longitude)

    companion object {
        private const val TAG = "MApp/Location"
        private const val CURRENT_TIMEOUT_MS = 5_000L
        private const val UPDATE_INTERVAL_MS = 15_000L

        @Volatile
        private var instance: LocationProvider? = null

        fun get(context: Context): LocationProvider =
            instance ?: synchronized(this) {
                instance ?: LocationProvider(context).also { instance = it }
            }
    }
}

/** Aguarda uma Task do Play Services; falha ou cancelamento viram null. */
private suspend fun <T> Task<T>.awaitOrNull(cts: CancellationTokenSource? = null): T? =
    suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
        }
        cont.invokeOnCancellation { cts?.cancel() }
    }
