package br.com.mapp.core

import br.com.mapp.core.earnings.DayEarningsScreen
import br.com.mapp.core.earnings.StableExtra
import br.com.mapp.core.earnings.UberExtra
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.OfferMetrics
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.Signal
import br.com.mapp.core.repo.EntryKind
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.repo.OfferRecord
import br.com.mapp.core.shift.PeriodMath
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class UberExtraTest {

    private val eps = 0.001
    private val zone = ZoneId.of("America/Sao_Paulo")
    private fun ms(day: Int, hour: Int, min: Int = 0) =
        ZonedDateTime.of(2026, 11, day, hour, min, 0, 0, zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ leitura da tela inicial

    // Tela inicial da Uber pela Acessibilidade (print de 25/11/2026: "R$ 147,25" com "HOJE" embaixo).
    private val home = listOf(
        "Página inicial", "R$ 147,25", "HOJE", "+R$ 1,25", "+R$ 1,50", "1-2 min", "1 min", "+R$ 1,75",
        "Rossi Caribe", "Você está online",
    )

    @Test fun le_o_ganho_de_hoje_da_uber() {
        assertEquals(147.25, DayEarningsScreen.read(Platform.UBER, home)!!, eps)
        // Diagnóstico de 22/11: dia zerado também é leitura (0 = nada a lançar).
        assertEquals(0.0, DayEarningsScreen.read(Platform.UBER, listOf("Página inicial", "R$ 0,00", "HOJE", "Você está online"))!!, eps)
    }

    @Test fun variacoes_do_valor() {
        assertEquals(1147.25, DayEarningsScreen.read(Platform.UBER, listOf("R$ 1.147,25", "HOJE"))!!, eps)
        assertEquals(98.5, DayEarningsScreen.read(Platform.UBER, listOf("R$ 98,50", "Hoje"))!!, eps)
        assertEquals(98.5, DayEarningsScreen.read(Platform.UBER, listOf("R$98,50", "TODAY"))!!, eps)
        assertEquals(12.0, DayEarningsScreen.read(Platform.UBER, listOf("R$ 12,00, HOJE"))!!, eps)
        // Ícone/descrição entre o valor e o rótulo.
        assertEquals(60.1, DayEarningsScreen.read(Platform.UBER, listOf("R$ 60,10", "Abrir ganhos", "HOJE"))!!, eps)
    }

    @Test fun sem_hoje_ou_valor_de_bolha_nao_le() {
        // Sem o rótulo: pode ser outro período ou outro valor da tela.
        assertNull(DayEarningsScreen.read(Platform.UBER, listOf("R$ 147,25", "Você está online")))
        // Bolha de dinâmica do mapa ("+R$") antes do rótulo não é o ganho.
        assertNull(DayEarningsScreen.read(Platform.UBER, listOf("+R$ 1,25", "HOJE")))
        // Valor escondido pelo motorista.
        assertNull(DayEarningsScreen.read(Platform.UBER, listOf("R$ ••••", "HOJE")))
        // Oferta (valor com texto junto) não vira ganho do dia.
        assertNull(DayEarningsScreen.read(Platform.UBER, listOf("R$ 12,34 Est.", "HOJE agora")))
        // 99 e inDrive ainda sem print da tela inicial.
        assertNull(DayEarningsScreen.read(Platform.NINETY_NINE, listOf("R$ 147,25", "HOJE")))
    }

    // ------------------------------------------------------------------ dia da Uber (4h às 4h)

    @Test fun dia_da_uber_vira_as_4h() {
        val day = UberExtra.uberDay(ms(25, 19, 40), zone)
        assertEquals(ms(25, 4), day.from)
        assertEquals(ms(26, 4) - 1, day.to)
        // Madrugada antes das 4h ainda é o dia anterior.
        assertEquals(ms(24, 4), UberExtra.uberDay(ms(25, 3, 59), zone).from)
        assertEquals(ms(25, 4), UberExtra.uberDay(ms(25, 4), zone).from)
    }

    // ------------------------------------------------------------------ conta do extra

    private fun trip(price: Double, at: Long, platform: Platform = Platform.UBER, finished: Boolean = true) =
        ShiftTrip(at, platform, "UberX", price, 1.0, 5.0, finished)

    private fun accepted(price: Double, at: Long, platform: Platform = Platform.UBER) = OfferRecord(
        id = 0,
        evaluated = EvaluatedOffer(
            raw = RawOffer(platform, price, tripKm = 5.0, capturedAt = at),
            metrics = OfferMetrics(6.0, 15, null, null, null, null),
            signal = Signal.GREEN,
            reasons = emptyList(),
        ),
        accepted = true,
    )

    private fun income(amount: Double, at: Long, platform: Platform? = Platform.UBER, category: String = "Uber", id: Long = 0) =
        FinanceEntry(id = id, kind = EntryKind.INCOME, category = category, amount = amount, platform = platform, atMillis = at)

    @Test fun diferenca_vira_ganho_extra() {
        val now = ms(25, 19, 40)
        val window = UberExtra.uberDay(now, zone)
        // Jornada de hoje: 3 corridas Uber finalizadas, 1 em andamento (não conta), 1 da 99 (não conta).
        val shift = Shift(
            startedAt = ms(25, 8),
            trips = listOf(
                trip(40.0, ms(25, 9)), trip(50.0, ms(25, 12)), trip(30.0, ms(25, 15)),
                trip(20.0, ms(25, 19), finished = false), trip(18.0, ms(25, 16), Platform.NINETY_NINE),
            ),
        )
        // Corrida aceita fora da jornada (antes de dar play) + ganho Uber lançado à mão + ganho da 99.
        val offers = listOf(accepted(15.0, ms(25, 7)), accepted(9.0, ms(25, 6), Platform.NINETY_NINE))
        val entries = listOf(income(5.0, ms(25, 10)), income(7.0, ms(25, 11), Platform.NINETY_NINE, "99"))
        val c = UberExtra.check(147.25, window, shift, emptyList(), offers, entries, now)
        assertEquals(135.0, c.rides, eps) // 40 + 50 + 30 + 15
        assertEquals(4, c.rideCount)
        assertEquals(5.0, c.manual, eps)
        assertEquals(7.25, c.target, eps) // 147,25 − 135 − 5
        assertEquals(0.0, c.saved, eps)
        assertNull(c.kept)

        val e = UberExtra.entry(c, c.target, now)!!
        assertEquals(EntryKind.INCOME, e.kind)
        assertEquals(UberExtra.CATEGORY, e.category)
        assertEquals(Platform.UBER, e.platform)
        assertEquals(7.25, e.amount, eps)
        assertNull(e.km) // não mexe nos km nem no custo do carro
        assertEquals(now, e.atMillis)
        assertTrue(e.note!!.contains("R$ 147,25"))
    }

    @Test fun extra_ja_lancado_e_atualizado_sem_somar_duas_vezes() {
        val now = ms(25, 20)
        val window = UberExtra.uberDay(now, zone)
        val shift = Shift(startedAt = ms(25, 8), trips = listOf(trip(100.0, ms(25, 9))))
        val extra = income(3.0, ms(25, 14), category = UberExtra.CATEGORY, id = 42)
        val c = UberExtra.check(110.0, window, shift, emptyList(), emptyList(), listOf(extra), now)
        // O extra lançado não conta como ganho "à mão": o alvo continua 10, não 7.
        assertEquals(0.0, c.manual, eps)
        assertEquals(10.0, c.target, eps)
        assertEquals(3.0, c.saved, eps)
        assertTrue(c.differs(c.target))
        val e = UberExtra.entry(c, c.target, now)!!
        assertEquals(42L, e.id) // atualiza o mesmo lançamento
        assertEquals(ms(25, 14), e.atMillis) // e mantém o horário dele
    }

    @Test fun mapp_acima_da_uber_nao_lanca_negativo() {
        val now = ms(25, 20)
        val window = UberExtra.uberDay(now, zone)
        val shift = Shift(startedAt = ms(25, 8), trips = listOf(trip(100.0, ms(25, 9))))
        val extra = income(3.0, ms(25, 14), category = UberExtra.CATEGORY, id = 7)
        val c = UberExtra.check(95.0, window, shift, emptyList(), emptyList(), listOf(extra), now)
        assertEquals(0.0, c.target, eps)
        assertEquals(5.0, c.mappAhead, eps)
        assertNull(UberExtra.entry(c, c.target, now)) // apagar o extra que havia
    }

    @Test fun so_conta_o_dia_da_uber() {
        val now = ms(25, 10)
        val window = UberExtra.uberDay(now, zone)
        // Jornada que começou ontem à noite: corrida das 2h é do dia anterior da Uber; a das 5h é de hoje.
        val shift = Shift(
            startedAt = ms(24, 22),
            trips = listOf(trip(30.0, ms(25, 2)), trip(25.0, ms(25, 5))),
        )
        val old = income(4.0, ms(25, 1), category = UberExtra.CATEGORY, id = 1) // extra de ontem fica onde está
        val c = UberExtra.check(25.0, window, shift, emptyList(), emptyList(), listOf(old), now)
        assertEquals(25.0, c.rides, eps)
        assertEquals(0.0, c.target, eps)
        assertEquals(0.0, c.saved, eps)
        assertTrue(c.extras.isEmpty())
    }

    @Test fun duplicado_e_apagado() {
        val now = ms(25, 20)
        val window = UberExtra.uberDay(now, zone)
        val a = income(2.0, ms(25, 12), category = UberExtra.CATEGORY, id = 1)
        val b = income(2.0, ms(25, 13), category = UberExtra.CATEGORY, id = 2)
        val c = UberExtra.check(2.0, window, null, emptyList(), emptyList(), listOf(b, a), now)
        assertEquals(1L, c.kept!!.id)
        assertEquals(4.0, c.saved, eps)
        assertTrue(c.differs(2.0))
    }

    @Test fun corridas_soltas_iguais_as_do_periodo() {
        // looseOffers é a mesma regra do PeriodMath.totals: aceita no horário de uma jornada não conta.
        val shift = Shift(startedAt = ms(25, 8), resumedAt = null, activeMs = 4 * 3_600_000L, endedAt = ms(25, 12))
        val offers = listOf(accepted(15.0, ms(25, 7)), accepted(20.0, ms(25, 9)), accepted(11.0, ms(25, 13)))
        val loose = PeriodMath.looseOffers(ms(25, 4), ms(26, 4) - 1, null, listOf(shift), offers, ms(25, 20))
        assertEquals(listOf(15.0, 11.0), loose.map { it.evaluated.raw.price })
    }

    // ------------------------------------------------------------------ espera até o valor firmar

    @Test fun so_grava_depois_de_firme() {
        val s = StableExtra(upMs = 30_000, downMs = 120_000)
        val day = ms(25, 4)
        val t0 = ms(25, 19)
        assertNull(s.offer(7.25, 0.0, day, t0))
        assertNull(s.offer(7.25, 0.0, day, t0 + 20_000))
        assertEquals(7.25, s.offer(7.25, 0.0, day, t0 + 30_000)!!, eps)
        // Valor mudou: recomeça a espera.
        assertNull(s.offer(9.0, 7.25, day, t0 + 40_000))
        assertNull(s.offer(9.0, 7.25, day, t0 + 60_000))
        assertNotNull(s.offer(9.0, 7.25, day, t0 + 70_000))
    }

    @Test fun descer_espera_mais() {
        // Corrida acabou: o MApp já somou e a Uber ainda não — o extra cairia para 0 e voltaria.
        val s = StableExtra(upMs = 30_000, downMs = 120_000)
        val day = ms(25, 4)
        val t0 = ms(25, 19)
        assertNull(s.offer(0.0, 7.25, day, t0))
        assertNull(s.offer(0.0, 7.25, day, t0 + 60_000))
        // Uber atualizou: volta ao valor de antes, nada muda.
        assertNull(s.offer(7.25, 7.25, day, t0 + 70_000))
        // Queda que se mantém por 2 min vale.
        assertNull(s.offer(5.0, 7.25, day, t0 + 80_000))
        assertEquals(5.0, s.offer(5.0, 7.25, day, t0 + 200_000)!!, eps)
    }

    @Test fun corrida_aberta_ou_dia_novo_recomeca() {
        val s = StableExtra(upMs = 30_000, downMs = 120_000)
        val t0 = ms(25, 19)
        assertNull(s.offer(3.0, 0.0, ms(25, 4), t0))
        s.reset()
        assertNull(s.offer(3.0, 0.0, ms(25, 4), t0 + 40_000))
        assertNull(s.offer(3.0, 0.0, ms(26, 4), t0 + 80_000)) // virou o dia da Uber
        assertEquals(3.0, s.offer(3.0, 0.0, ms(26, 4), t0 + 110_000)!!, eps)
    }
}
