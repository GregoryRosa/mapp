package br.com.mapp.core.sound

/** Som tocado quando uma oferta aparece no Card. */
enum class OfferSound { STOP, GREAT }

/**
 * Qual som toca para a oferta (um só, antes da voz):
 * - **parada no caminho** ("1 parada" no cartão da Uber) com o aviso ligado → som de parada, para o
 *   motorista decidir se recusa (o MApp nunca recusa sozinho);
 * - senão, tudo verde no Card → som de corrida ótima.
 * O aviso de parada vem antes: corrida ótima com parada toca só o aviso de parada.
 */
object OfferSounds {
    /** Som padrão do aviso de parada (diferente do Sino da corrida ótima). */
    val STOP_DEFAULT: GreatSound = GreatSound.DOUBLE_BEEP

    fun pick(hasStops: Boolean, allGreen: Boolean, stopSound: GreatSound): OfferSound? = when {
        hasStops && stopSound != GreatSound.OFF -> OfferSound.STOP
        allGreen -> OfferSound.GREAT
        else -> null
    }
}
