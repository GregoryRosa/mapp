package br.com.mapp.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import br.com.mapp.core.geo.PhotoView
import br.com.mapp.core.geo.SatelliteShot
import br.com.mapp.core.geo.SatelliteView
import br.com.mapp.core.geo.SatelliteZoom
import br.com.mapp.core.geo.StreetFraming
import br.com.mapp.core.geo.StreetView
import br.com.mapp.core.geo.StreetViewBudget
import br.com.mapp.core.geo.StreetZoom
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.route.RouteStop
import br.com.mapp.maps.MapViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Busca as fotos dos pontos da corrida: foto da rua (Street View) ou vista de cima (satélite, Maps
 * Static API). Só manda ao Google a coordenada do ponto (e a letra/cor do marcador, no satélite) e a
 * chave colada pelo usuário em Card → Card de corridas. Imagens ficam só na memória (cache pequeno).
 */
object StreetViewPhotos {
    private const val TAG = "MApp/StreetView"
    private const val TIMEOUT_MS = 6_000

    sealed interface Result {
        /**
         * [satellite] = vista de cima: o logo e os créditos do Google estão dentro da imagem, então
         * ela aparece inteira (sem recorte) e sem o crédito desenhado por cima ([credit] vazio).
         */
        data class Photo(val bitmap: Bitmap, val credit: String, val satellite: Boolean = false) : Result

        /** [retry] = falha passageira (rede, chave): tenta de novo no próximo desenho do Card. */
        data class Missing(val message: String, val retry: Boolean = false) : Result
    }

    /**
     * ~12 MB de imagens (≈ 11 de 640×420: os 3 pontos de uma corrida nos dois modos e mais algumas);
     * "sem foto aqui" também fica guardado.
     */
    private val cache = object : LruCache<String, Result>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Result): Int =
            if (value is Result.Photo) value.bitmap.byteCount else 1
    }

    private val budgetLock = Any()

    /**
     * Foto da rua já na memória do ponto com o [zoom], o [framing] e a inclinação [tilt] escolhidos
     * (mudar qualquer um deles nos ajustes busca de novo).
     */
    fun cached(
        point: GeoPoint,
        zoom: StreetZoom,
        framing: StreetFraming = StreetFraming.FACADE,
        tilt: Int = 0,
    ): Result? = cache.get(StreetView.cacheKey(point, zoom, framing, tilt))

    /**
     * Imagem já na memória do ponto [stop] no modo [view] (null = ainda não buscada). [shot] = tamanho
     * do satélite (Card ou faixa da notificação/Atividades).
     */
    fun cached(
        stop: RouteStop,
        view: PhotoView,
        shot: SatelliteShot = SatelliteShot.CARD,
        zoom: StreetZoom = StreetZoom.DEFAULT,
        satelliteZoom: SatelliteZoom = SatelliteZoom.DEFAULT,
        framing: StreetFraming = StreetFraming.FACADE,
        tilt: Int = 0,
    ): Result? {
        val point = stop.point ?: return null
        return when (view) {
            PhotoView.STREET -> cached(point, zoom, framing, tilt)
            PhotoView.SATELLITE -> cache.get(satelliteKey(stop, point, shot.zoomed(satelliteZoom)))
        }
    }

    suspend fun load(context: Context, point: GeoPoint): Result {
        val key = OverlayPrefs.streetViewKey(context)
            ?: return Result.Missing("Cole a chave do Google em Card", retry = true)
        val zoom = OverlayPrefs.streetZoom(context)
        val framing = OverlayPrefs.streetFraming(context)
        val tilt = OverlayPrefs.streetTilt(context)
        cached(point, zoom, framing, tilt)?.let { return it }
        val result = withContext(Dispatchers.IO) {
            fetch(context.applicationContext, point, key, zoom, framing, tilt)
        }
        if (!(result is Result.Missing && result.retry)) {
            cache.put(StreetView.cacheKey(point, zoom, framing, tilt), result)
        }
        return result
    }

    /** Foto da rua ou vista de cima do ponto [stop] (com a letra e a cor dele no marcador do satélite). */
    suspend fun load(
        context: Context,
        stop: RouteStop,
        view: PhotoView,
        shot: SatelliteShot = SatelliteShot.CARD,
    ): Result {
        val point = stop.point ?: return Result.Missing("Endereço não lido", retry = true)
        if (view == PhotoView.STREET) return load(context, point)
        val key = OverlayPrefs.streetViewKey(context)
            ?: return Result.Missing("Cole a chave do Google em Card", retry = true)
        // Zoom escolhido em Card → Fotos da rua e satélite, somado ao do formato do quadro.
        val zoomed = shot.zoomed(OverlayPrefs.satelliteZoom(context))
        val id = satelliteKey(stop, point, zoomed)
        cache.get(id)?.let { return it }
        val color = MapViews.colorOf(stop.kind)
        val result = withContext(Dispatchers.IO) { fetchSatellite(context.applicationContext, point, stop.letter, color, key, zoomed) }
        if (!(result is Result.Missing && result.retry)) cache.put(id, result)
        return result
    }

    private fun satelliteKey(stop: RouteStop, point: GeoPoint, shot: SatelliteShot) =
        SatelliteView.cacheKey(point, stop.letter, MapViews.colorOf(stop.kind), shot)

    /**
     * Vista de cima: uma chamada só (não há consulta grátis antes). Conta no limite do mês do
     * satélite só quando a imagem chega (pedido recusado pelo Google não é cobrado).
     */
    private fun fetchSatellite(
        context: Context,
        point: GeoPoint,
        letter: String,
        color: Int,
        key: String,
        shot: SatelliteShot,
    ): Result {
        val month = StreetViewBudget.monthOf(System.currentTimeMillis())
        synchronized(budgetLock) {
            if (!OverlayPrefs.satelliteBudget(context).canSpend(month, SatelliteView.LIMIT_PER_MONTH)) {
                return Result.Missing("Limite de satélite do mês", retry = true)
            }
        }
        return try {
            val (code, bytes) = get(SatelliteView.imageUrl(point, key, letter, color, shot))
            when (code) {
                200 -> {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return Result.Missing("Satélite indisponível", retry = true)
                    synchronized(budgetLock) {
                        OverlayPrefs.setSatelliteBudget(context, OverlayPrefs.satelliteBudget(context).spend(month))
                    }
                    Result.Photo(bitmap, credit = "", satellite = true)
                }
                // Chave sem a Maps Static API ativada (ou restrita a outras APIs).
                403 -> {
                    Log.w(TAG, "Satélite recusado: ${bytes.decodeToString().take(200)}")
                    Result.Missing("Ative a Maps Static API na chave", retry = true)
                }
                429 -> Result.Missing("Cota do Google esgotada", retry = true)
                else -> {
                    Log.w(TAG, "Satélite HTTP $code: ${bytes.decodeToString().take(200)}")
                    Result.Missing("Satélite indisponível", retry = true)
                }
            }
        } catch (e: IOException) {
            Result.Missing("Sem internet", retry = true)
        }
    }

    private fun fetch(
        context: Context,
        point: GeoPoint,
        key: String,
        zoom: StreetZoom,
        framing: StreetFraming,
        tilt: Int,
    ): Result {
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
            val (code, bytes) = get(StreetView.imageUrl(point, key, zoom, framing, tilt, panoOf(meta)))
            if (code != 200) return Result.Missing("Sem foto da rua aqui")
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return Result.Missing("Foto indisponível", retry = true)
            Result.Photo(bitmap, StreetView.credit(meta.optString("copyright"), meta.optString("date")))
        } catch (e: IOException) {
            Result.Missing("Sem internet", retry = true)
        }
    }

    /**
     * De onde a foto foi tirada (`location` dos metadados): é o ponto da câmera, dentro da rua.
     * O enquadramento Rua precisa dele para virar a câmera ao longo da via. null = não veio.
     */
    private fun panoOf(meta: JSONObject): GeoPoint? {
        val loc = meta.optJSONObject("location") ?: return null
        val lat = loc.optDouble("lat", Double.NaN)
        val lon = loc.optDouble("lng", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        return GeoPoint(lat, lon)
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
