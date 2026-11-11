package br.com.mapp.overlay

import android.content.Context
import android.util.Log
import br.com.mapp.MappApp
import br.com.mapp.core.bus.OfferBus
import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.FilterProfile
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.repo.Repositories
import br.com.mapp.maps.GeocodingRepository
import br.com.mapp.maps.LocationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Identidade da oferta (ignora texto bruto e horário), usada para não repetir registro/voz. */
internal fun RawOffer.identity(): RawOffer = copy(rawText = "", capturedAt = 0L)

/**
 * OfferBus.raw → (perfil, custos, zonas, posição, geocodificação) → OfferEvaluator
 * → OfferBus.publishEvaluated + histórico. Uma oferta nova cancela a avaliação anterior.
 */
class OfferPipeline(
    context: Context,
    private val repos: Repositories = MappApp.repos,
) {
    private val geocoder = GeocodingRepository.get(context)
    private val location = LocationProvider.get(context)

    private var lastIdentity: RawOffer? = null
    private var lastEvaluated: EvaluatedOffer? = null
    private var lastSeenAt = 0L

    /** Suspende enquanto o escopo estiver ativo (rodar no lifecycleScope do serviço). */
    suspend fun run() {
        OfferBus.raw.collectLatest { raw ->
            try {
                handle(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao avaliar oferta", e)
            }
        }
    }

    private suspend fun handle(raw: RawOffer) {
        val identity = raw.identity()
        val previous = lastEvaluated
        if (previous != null && identity == lastIdentity && raw.capturedAt - lastSeenAt < DEDUP_WINDOW_MS) {
            // O leitor republica a mesma oferta a cada evento de tela: só re-mostra, sem novo registro.
            lastSeenAt = raw.capturedAt
            OfferBus.publishEvaluated(previous)
            return
        }

        val evaluated = evaluate(raw)
        lastIdentity = identity
        lastEvaluated = evaluated
        lastSeenAt = raw.capturedAt
        OfferBus.publishEvaluated(evaluated)

        withContext(NonCancellable) {
            try {
                repos.offers.record(evaluated)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao gravar histórico", e)
            }
        }
    }

    suspend fun evaluate(raw: RawOffer): EvaluatedOffer = coroutineScope {
        val profile = async { orDefault(FilterProfile()) { repos.profiles.active().first() } }
        val cost = async { orDefault(VehicleCostConfig()) { repos.vehicle.config().first() } }
        val zones = async { orDefault(emptyList()) { repos.zones.all().first() } }

        val here: GeoPoint? = location.lastKnown ?: location.current(timeoutMs = POSITION_TIMEOUT_MS)
        val pickup = async { geocode(raw.pickupAddress, here) }
        val dropoff = async { geocode(raw.dropoffAddress, here) }

        OfferEvaluator.evaluate(
            raw = raw,
            profile = profile.await(),
            cost = cost.await(),
            zones = zones.await(),
            pickup = pickup.await(),
            dropoff = dropoff.await(),
        )
    }

    private suspend fun geocode(address: String?, near: GeoPoint?): GeoPoint? {
        if (address.isNullOrBlank()) return null
        return withTimeoutOrNull(GEOCODE_TIMEOUT_MS) { geocoder.geocode(address, near) }
    }

    private suspend fun <T> orDefault(default: T, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Usando valor padrão", e)
            default
        }

    private companion object {
        const val TAG = "MApp/Pipeline"
        const val GEOCODE_TIMEOUT_MS = 2_500L
        const val POSITION_TIMEOUT_MS = 1_500L
        const val DEDUP_WINDOW_MS = 60_000L
    }
}
