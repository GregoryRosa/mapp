package br.com.mapp.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import br.com.mapp.core.geo.StreetView
import br.com.mapp.core.geo.StreetViewBudget
import br.com.mapp.core.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Busca as fotos da rua do Card (Street View). Só manda ao Google a coordenada do ponto e a chave
 * colada pelo usuário em Card → Card de corridas. Fotos ficam só na memória (cache pequeno).
 */
object StreetViewPhotos {
    private const val TAG = "MApp/StreetView"
    private const val TIMEOUT_MS = 6_000

    sealed interface Result {
        data class Photo(val bitmap: Bitmap, val credit: String) : Result

        /** [retry] = falha passageira (rede, chave): tenta de novo no próximo desenho do Card. */
        data class Missing(val message: String, val retry: Boolean = false) : Result
    }

    /** ~8 MB de fotos (≈ 8 fotos de 640×420); "sem foto aqui" também fica guardado. */
    private val cache = object : LruCache<String, Result>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Result): Int =
            if (value is Result.Photo) value.bitmap.byteCount else 1
    }

    private val budgetLock = Any()

    fun cached(point: GeoPoint): Result? = cache.get(StreetView.cacheKey(point))

    suspend fun load(context: Context, point: GeoPoint): Result {
        val key = OverlayPrefs.streetViewKey(context)
            ?: return Result.Missing("Cole a chave do Google em Card", retry = true)
        cached(point)?.let { return it }
        val result = withContext(Dispatchers.IO) { fetch(context.applicationContext, point, key) }
        if (!(result is Result.Missing && result.retry)) cache.put(StreetView.cacheKey(point), result)
        return result
    }

    private fun fetch(context: Context, point: GeoPoint, key: String): Result {
        // 1) Metadados (grátis): há foto perto? Sem foto, nada é cobrado.
        val meta = try {
            val (code, body) = get(StreetView.metadataUrl(point, key))
            if (code != 200) return Result.Missing("Foto indisponível", retry = true)
            JSONObject(body.decodeToString())
        } catch (e: IOException) {
            return Result.Missing("Sem internet", retry = true)
        } catch (e: Exception) {
            Log.w(TAG, "Metadados inválidos", e)
            return Result.Missing("Foto indisponível", retry = true)
        }
        when (val status = meta.optString("status")) {
            "OK" -> Unit
            "ZERO_RESULTS", "NOT_FOUND" -> return Result.Missing("Sem foto da rua aqui")
            "REQUEST_DENIED" -> {
                Log.w(TAG, "Chave recusada: ${meta.optString("error_message")}")
                return Result.Missing("Chave recusada pelo Google", retry = true)
            }
            "OVER_QUERY_LIMIT" -> return Result.Missing("Cota do Google esgotada", retry = true)
            else -> {
                Log.w(TAG, "Status $status: ${meta.optString("error_message")}")
                return Result.Missing("Foto indisponível", retry = true)
            }
        }

        // 2) A foto conta no limite do mês (o MApp para antes das 10.000 grátis do Google).
        val month = StreetViewBudget.monthOf(System.currentTimeMillis())
        synchronized(budgetLock) {
            val budget = OverlayPrefs.streetViewBudget(context)
            if (!budget.canSpend(month)) return Result.Missing("Limite de fotos do mês", retry = true)
            OverlayPrefs.setStreetViewBudget(context, budget.spend(month))
        }
        return try {
            val (code, bytes) = get(StreetView.imageUrl(point, key))
            if (code != 200) return Result.Missing("Sem foto da rua aqui")
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return Result.Missing("Foto indisponível", retry = true)
            Result.Photo(bitmap, StreetView.credit(meta.optString("copyright"), meta.optString("date")))
        } catch (e: IOException) {
            Result.Missing("Sem internet", retry = true)
        }
    }

    private fun get(url: String): Pair<Int, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return code to bytes
        } finally {
            conn.disconnect()
        }
    }
}
