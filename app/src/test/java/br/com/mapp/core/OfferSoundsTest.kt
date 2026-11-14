package br.com.mapp.core

import br.com.mapp.core.sound.GreatSound
import br.com.mapp.core.sound.OfferSound
import br.com.mapp.core.sound.OfferSounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfferSoundsTest {
    @Test fun parada_toca_o_aviso_mesmo_em_corrida_otima() {
        assertEquals(OfferSound.STOP, OfferSounds.pick(hasStops = true, allGreen = false, stopSound = GreatSound.DOUBLE_BEEP))
        assertEquals(OfferSound.STOP, OfferSounds.pick(hasStops = true, allGreen = true, stopSound = GreatSound.CHIME))
    }

    @Test fun aviso_de_parada_desligado_volta_ao_som_de_corrida_otima() {
        assertEquals(OfferSound.GREAT, OfferSounds.pick(hasStops = true, allGreen = true, stopSound = GreatSound.OFF))
        assertNull(OfferSounds.pick(hasStops = true, allGreen = false, stopSound = GreatSound.OFF))
    }

    @Test fun sem_parada() {
        assertEquals(OfferSound.GREAT, OfferSounds.pick(hasStops = false, allGreen = true, stopSound = GreatSound.DOUBLE_BEEP))
        assertNull(OfferSounds.pick(hasStops = false, allGreen = false, stopSound = GreatSound.DOUBLE_BEEP))
        assertEquals(GreatSound.DOUBLE_BEEP, OfferSounds.STOP_DEFAULT)
    }
}
