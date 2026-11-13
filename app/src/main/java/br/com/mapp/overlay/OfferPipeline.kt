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
import br.com.mapp.core.model.Zone
import br.com.mapp.core.repo.Repositories
import br.com.mapp.maps.GeocodingRepository
import br.com.mapp.maps.LocationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Identidade da oferta (ignora texto bruto, horário e o tipo — o OCR às vezes não lê o "UberX"),
 * usada para não repetir registro/voz.
 */
internal fun RawOffer.identity(): RawOffer = copy(rawText = "", capturedAt = 0L, category = null)

/**
 * OfferBus.raw → OfferEvaluator → OfferBus.publishEvaluated + histórico, em duas etapas:
 *  1. na hora: semáforo e valores com perfil/custos já em memória (sem esperar GPS nem rede);
 *  2. depois: posição + geocodificação da coleta/destino → reavalia com zonas e atualiza o mapa.
 * Uma oferta nova cancela a avaliação anterior; a mesma oferta republicada não reinicia nada.
 */
class OfferPipeline(
    context: Context,
    private val repos: Repositories = MappApp.repos,
) {
    private val geocoder = GeocodingRepository.get(context)
    private val location = LocationProvider.get(context)

    // Configuração em memória (atualizada pelo Room): a 1ª etapa não espera o banco.
    @Volatile private var profile: FilterProfile? = null
    @Volatile private var cost: VehicleCostConfig? = null
    @Volatile private var zones: List<Zone>? = null

    private var lastIdentity: RawOffer? = null
    private var lastEvaluated: EvaluatedOffer? = null
    private var lastSeenAt = 0L
    private var job: Job? = null
    private var evaluation: Run? = null

    /** Uma avaliação. [superseded] = a mesma oferta chegou completada: esta não grava no histórico. */
    private class Run(val raw: RawOffer) {
        @Volatile var superseded = false
    }

    /** Suspende enquanto o escopo estiver ativo (rodar no lifecycleScope do serviço). */
    suspend fun run(): Unit = coroutineScope {
        launch { keep(repos.profiles.active()) { profile = it } }
        launch { keep(repos.vehicle.config()) { cost = it } }
        launch { keep(repos.zones.all()) { zones = it } }

        OfferBus.raw.collect { raw ->
            val identity = raw.identity()
            if (identity == lastIdentity && raw.capturedAt - lastSeenAt < DEDUP_WINDOW_MS) {
                // O leitor republicou a mesma oferta: re-mostra e deixa a avaliação em curso terminar.
                lastSeenAt = raw.capturedAt
                lastEvaluated?.let { OfferBus.publishEvaluated(it) }
                return@collect
            }
            // Mesmo horário de 1ª leitura = a oferta na tela, completada pelo leitor (tempo que faltava,
            // endereço): reavalia, e só a avaliação nova grava (substitui a linha no histórico).
            val previous = evaluation
            if (previous != null && previous.raw.capturedAt == raw.capturedAt && previous.raw.platform == raw.platform) {
                previous.superseded = true
            }
            job?.cancel()
            lastIdentity = identity
            lastEvaluated = null
            lastSeenAt = raw.capturedAt
            val current = Run(raw).also { evaluation = it }
            job = launch {
                try {
                    handle(current)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao avaliar oferta", e)
                }
            }
        }
    }

    private suspend fun handle(current: Run) {
        val raw = current.raw
        val profile = profile ?: orDefault(FilterProfile()) { repos.profiles.active().first() }
        val cost = cost ?: orDefault(VehicleCostConfig()) { repos.vehicle.config().first() }
        val zones = zones ?: orDefault(emptyList()) { repos.zones.all().first() }

        // Etapa 1: semáforo imediato.
        var result = OfferEvaluator.evaluate(raw, profile, cost)
        show(result)

        try {
            // Etapa 2: coleta/destino no mapa e zonas.
            if (!raw.pickupAddress.isNullOrBlank() || !raw.dropoffAddress.isNullOrBlank()) {
                val located = coroutineScope {
                    val here: GeoPoint? = location.lastKnown
                        ?: withTimeoutOrNull(POSITION_TIMEOUT_MS) { location.current(POSITION_TIMEOUT_MS) }
                    val pickup = async { geocode(raw.pickupAddress, here) }
                    val dropoff = async { geocode(raw.dropoffAddress, here) }
                    pickup.await() to dropoff.await()
                }
                if (located.first != null || located.second != null) {
                    result = OfferEvaluator.evaluate(raw, profile, cost, zones, located.first, located.second)
                    show(result)
                }
            }
        } finally {
            if (!current.superseded) withContext(NonCancellable) {
                try {
                    repos.offers.record(result)
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao gravar histórico", e)
                }
            }
        }
    }

    private fun show(result: EvaluatedOffer) {
        lastEvaluated = result
        OfferBus.publishEvaluated(result)
    }

    private suspend fun geocode(address: String?, near: GeoPoint?): GeoPoint? {
        if (address.isNullOrBlank()) return null
        return withTimeoutOrNull(GEOCODE_TIMEOUT_MS) { geocoder.geocode(address, near) }
    }

    private suspend fun <T> keep(flow: Flow<T>, set: (T) -> Unit) {
        try {
            flow.collect { set(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao observar configuração", e)
        }
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
        const val GEOCODE_TIMEOUT_MS = 4_000L
        const val POSITION_TIMEOUT_MS = 1_500L
        const val DEDUP_WINDOW_MS = 60_000L
    }
}
