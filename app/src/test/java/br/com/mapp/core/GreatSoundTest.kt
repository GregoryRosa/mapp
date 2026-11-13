package br.com.mapp.core

import br.com.mapp.core.sound.GreatSound
import br.com.mapp.core.sound.SoundSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GreatSoundTest {

    @Test fun sons_gerados_comecam_e_terminam_em_silencio() {
        GreatSound.entries.filter { it.synthesized }.forEach { sound ->
            val pcm = SoundSynth.render(sound)
            val expected = SoundSynth.durationMs(sound) * SoundSynth.SAMPLE_RATE / 1000
            assertTrue("$sound vazio", pcm.isNotEmpty())
            assertTrue("$sound com tamanho errado", abs(pcm.size - expected) <= SoundSynth.notes(sound).size)
            assertTrue("$sound curto/longo demais", SoundSynth.durationMs(sound) in 300L..1500L)
            assertEquals("$sound começa com estalo", 0, pcm.first().toInt())
            assertTrue("$sound termina com estalo", abs(pcm.last().toInt()) < 300)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$sound baixo demais", peak > Short.MAX_VALUE * 0.5)
            assertTrue("$sound distorce", peak <= Short.MAX_VALUE * SoundSynth.PEAK + 1)
        }
    }

    @Test fun sem_som_e_som_do_celular_nao_geram_nada() {
        assertEquals(0, SoundSynth.render(GreatSound.OFF).size)
        assertEquals(0, SoundSynth.render(GreatSound.PHONE).size)
        assertEquals(0L, SoundSynth.durationMs(GreatSound.OFF))
    }

    @Test fun escolha_salva_volta_igual() {
        GreatSound.entries.forEach { assertEquals(it, GreatSound.fromId(it.id)) }
        assertEquals(GreatSound.entries.size, GreatSound.entries.map { it.id }.toSet().size)
        assertEquals(GreatSound.DEFAULT, GreatSound.fromId(null))
        assertEquals(GreatSound.DEFAULT, GreatSound.fromId("lixo"))
    }
}
