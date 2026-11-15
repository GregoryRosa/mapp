package br.com.mapp.core.engine

import br.com.mapp.core.model.RawOffer

/**
 * Filtro de ofertas que não existem: o leitor às vezes junta números de outra tela (ex.: ganhos
 * "R$ 220,74" + navegação "17 min · 10,1 km" durante a viagem) e monta uma oferta falsa.
 * Oferta rejeitada não mostra Card, não fala, não grava no histórico e não entra na jornada.
 *
 * Regras:
 *  - **incompleta**: sem nada da busca (km e minutos) e sem nenhum endereço — todo cartão de oferta
 *    real mostra a busca e os endereços;
 *  - **valor impossível** (só a partir de [MIN_PRICE_TO_JUDGE]): R$/km acima de [MAX_PER_KM] **e**
 *    R$/h acima de [MAX_PER_HOUR] (o que der para calcular). Dinâmica alta passa de um, não dos dois.
 */
object OfferSanity {
    const val MAX_PER_KM = 12.0
    const val MAX_PER_HOUR = 300.0
    const val MIN_PRICE_TO_JUDGE = 25.0

    /** Motivo da rejeição, ou null se a oferta é plausível. */
    fun problem(raw: RawOffer): String? {
        if (!raw.price.isFinite() || raw.price <= 0.0) return "sem valor"
        val noPickup = raw.pickupKm == null && raw.pickupMin == null
        val noAddress = raw.pickupAddress.isNullOrBlank() && raw.dropoffAddress.isNullOrBlank() &&
            raw.stopAddresses.none { it.isNotBlank() }
        if (noPickup && noAddress) return "sem busca nem endereços"

        if (raw.price < MIN_PRICE_TO_JUDGE) return null
        val legs = OfferEvaluator.legs(raw)
        val perKm = legs.km?.takeIf { it > 0 }?.let { raw.price / it }
        val perHour = legs.minutes?.takeIf { it > 0 }?.let { raw.price / it * 60.0 }
        if (perKm == null && perHour == null) return null
        val kmTooHigh = perKm == null || perKm > MAX_PER_KM
        val hourTooHigh = perHour == null || perHour > MAX_PER_HOUR
        return if (kmTooHigh && hourTooHigh) "valor impossível para a distância/tempo" else null
    }

    fun isPlausible(raw: RawOffer): Boolean = problem(raw) == null
}
