package br.com.mapp.core.bus

import br.com.mapp.core.copilot.OnlineState
import br.com.mapp.core.copilot.RideOnline
import br.com.mapp.core.earnings.DayEarnings
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.TripEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Canal em processo entre o leitor (Acessibilidade) e o overlay. */
object OfferBus {
    private val _raw = MutableSharedFlow<RawOffer>(extraBufferCapacity = 8)
    val raw: SharedFlow<RawOffer> = _raw.asSharedFlow()

    private val _evaluated = MutableStateFlow<EvaluatedOffer?>(null)
    /** Última oferta avaliada (null = nenhuma oferta na tela). */
    val evaluated: StateFlow<EvaluatedOffer?> = _evaluated.asStateFlow()

    /**
     * true enquanto alguém consome [raw] (copiloto ligado). O leitor só captura a imagem da tela
     * nesse caso — com o copiloto parado não há por que gastar bateria.
     */
    val hasListeners: Boolean get() = _raw.subscriptionCount.value > 0

    /** Oferta atualmente na tela (null após clear). Evita mostrar avaliação atrasada de oferta que já sumiu. */
    @Volatile private var current: RawOffer? = null

    /** Chamado pelo leitor quando uma nova oferta aparece. */
    fun publish(offer: RawOffer) {
        current = offer
        _raw.tryEmit(offer)
    }

    /** Chamado pelo pipeline após avaliar a oferta. Ignorado se a oferta não está mais na tela. */
    fun publishEvaluated(offer: EvaluatedOffer?) {
        if (offer == null || sameOffer(offer.raw, current)) _evaluated.value = offer
    }

    private val _tripStages = MutableSharedFlow<TripEvent>(extraBufferCapacity = 8)
    /** Corrida aceita / em viagem / finalizada, reconhecida na tela (jornada automática). */
    val tripStages: SharedFlow<TripEvent> = _tripStages.asSharedFlow()

    /** Chamado pelo leitor quando a tela muda de etapa ou de passageiro (sem repetir). */
    fun publishTripStage(event: TripEvent) {
        _tripStages.tryEmit(event)
    }

    private val _rideOnline = MutableSharedFlow<RideOnline>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /**
     * Motorista online/offline na tela inicial do app de corrida, a cada leitura do texto — com o
     * copiloto ligado ou não (é o que liga o copiloto sozinho: `overlay/CopilotAutoStart`).
     */
    val rideOnline: SharedFlow<RideOnline> = _rideOnline.asSharedFlow()

    /** Chamado pelo leitor (qualquer thread) quando a tela diz se o motorista está online ou offline. */
    fun publishRideOnline(platform: Platform, state: OnlineState) {
        _rideOnline.tryEmit(RideOnline(platform, state))
    }

    private val _dayEarnings = MutableSharedFlow<DayEarnings>(
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /**
     * Ganho de hoje mostrado na tela inicial do app de corrida ("R$ 147,25" + "HOJE" na Uber), a cada
     * leitura do texto: o copiloto compara com as corridas contadas e lança o ganho extra
     * (`overlay/UberExtraSync`, regras em `core/earnings/UberExtra`).
     */
    val dayEarnings: SharedFlow<DayEarnings> = _dayEarnings.asSharedFlow()

    /** Chamado pelo leitor (qualquer thread) quando lê o ganho de hoje na tela inicial. */
    fun publishDayEarnings(platform: Platform, amount: Double) {
        _dayEarnings.tryEmit(DayEarnings(platform, amount))
    }

    /** Chamado pelo leitor quando a tela de oferta some. */
    fun clear() {
        current = null
        _evaluated.value = null
    }

    private fun sameOffer(a: RawOffer, b: RawOffer?): Boolean =
        b != null && a.copy(rawText = "", capturedAt = 0, category = null) == b.copy(rawText = "", capturedAt = 0, category = null)
}
