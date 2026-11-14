package br.com.mapp.finance

import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.OfferMetrics
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.Signal
import br.com.mapp.core.repo.EntryKind
import br.com.mapp.core.repo.FinanceEntry
import br.com.mapp.core.repo.OfferRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReportMathTest {
    private val zone: ZoneId = ZoneId.of("America/Sao_Paulo")

    /** 2026-11-09 é uma segunda-feira. */
    private fun t(day: Int, hour: Int, minute: Int = 0, month: Int = 11): Long =
        LocalDateTime.of(2026, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun income(amount: Double, at: Long, km: Double? = null) =
        FinanceEntry(kind = EntryKind.INCOME, category = "Uber", amount = amount, platform = Platform.UBER, km = km, atMillis = at)

    private fun expense(amount: Double, at: Long) =
        FinanceEntry(kind = EntryKind.EXPENSE, category = "Combustível", amount = amount, atMillis = at)

    private fun offer(price: Double, km: Double?, min: Int?, at: Long, signal: Signal = Signal.YELLOW) = OfferRecord(
        id = 0,
        evaluated = EvaluatedOffer(
            raw = RawOffer(Platform.UBER, price, capturedAt = at),
            metrics = OfferMetrics(km, min, null, null, null, null),
            signal = signal,
            reasons = emptyList(),
        ),
        accepted = null,
    )

    @Test fun resumo_das_aceitas_ignora_nao_aceitas_e_sem_marca() {
        fun rec(price: Double, km: Double, min: Int, profit: Double?, accepted: Boolean?) = OfferRecord(
            id = 0,
            evaluated = EvaluatedOffer(
                raw = RawOffer(Platform.UBER, price, capturedAt = t(9, 8)),
                metrics = OfferMetrics(km, min, null, null, profit, null),
                signal = Signal.GREEN,
                reasons = emptyList(),
            ),
            accepted = accepted,
        )
        val list = listOf(
            rec(20.0, 10.0, 20, 12.0, true),
            rec(30.0, 10.0, 40, null, true),
            rec(99.0, 5.0, 10, 50.0, false),
            rec(15.0, 5.0, 10, 8.0, null),
        )
        val s = ReportMath.summarizeAccepted(list)
        assertEquals(2, s.count)
        assertEquals(50.0, s.total, 1e-9)
        assertEquals(12.0, s.profit!!, 1e-9)
        assertEquals(2.5, s.avgPerKm!!, 1e-9)
        assertEquals(50.0, s.avgPerHour!!, 1e-9)
        assertEquals(2, ReportMath.accepted(list).size)

        val rides = ReportMath.ridesAsIncome(list)
        assertEquals(2, rides.size)
        assertEquals(50.0, ReportMath.summarize(rides).income, 1e-9)
        assertEquals(20.0, ReportMath.summarize(rides).km, 1e-9)

        val none = ReportMath.summarizeAccepted(list.filter { it.accepted != true })
        assertEquals(0, none.count)
        assertNull(none.profit)
        assertNull(none.avgPerKm)
    }

    @Test fun resumo_soma_ganhos_gastos_lucro_e_km() {
        val s = ReportMath.summarize(
            listOf(
                income(100.0, t(9, 8), km = 40.0),
                income(50.0, t(9, 9)), // sem km: entra no ganho, não no R$/km
                expense(30.0, t(9, 10)),
            )
        )
        assertEquals(150.0, s.income, 1e-9)
        assertEquals(30.0, s.expense, 1e-9)
        assertEquals(120.0, s.profit, 1e-9)
        assertEquals(40.0, s.km, 1e-9)
        assertEquals(2.5, s.incomePerKm!!, 1e-9)
        assertEquals(2, s.incomeCount)
        assertEquals(1, s.expenseCount)
    }

    @Test fun resumo_vazio_nao_tem_rs_por_km() {
        val s = ReportMath.summarize(emptyList())
        assertEquals(0.0, s.profit, 1e-9)
        assertNull(s.incomePerKm)
    }

    @Test fun periodos_hoje_mes_e_ultimos_dias() {
        val now = t(11, 15)
        val today = ReportMath.today(now, zone)
        assertEquals(t(11, 0), today.from)
        assertEquals(t(12, 0) - 1, today.to)

        val week = ReportMath.lastDays(7, now, zone)
        assertEquals(t(5, 0), week.from)
        assertEquals(today.to, week.to)

        val month = ReportMath.currentMonth(now, zone)
        assertEquals(t(1, 0), month.from)
        assertEquals(t(1, 0, month = 12) - 1, month.to)

        val entries = listOf(income(10.0, t(11, 1)), income(20.0, t(10, 23)))
        assertEquals(10.0, ReportMath.summarize(entries, today).income, 1e-9)
    }

    @Test fun dia_de_trabalho_segue_a_jornada_aberta_depois_da_meia_noite() {
        // Jornada aberta às 18h do dia 13; agora 00:30 do dia 14 → o dia ainda é o 13.
        val started = t(13, 18)
        val afterMidnight = t(14, 0) + 30 * 60_000L
        // Período vai de 00:00 do dia 13 até o fim do dia 14 (corridas depois da meia-noite entram).
        assertEquals(Period(t(13, 0), t(15, 0) - 1), ReportMath.workDay(started, afterMidnight, zone))
        assertEquals(java.time.LocalDate.of(2026, 11, 13), ReportMath.workDate(started, afterMidnight, zone))
        // Sem jornada aberta: o dia do calendário.
        assertEquals(ReportMath.today(afterMidnight, zone), ReportMath.workDay(null, afterMidnight, zone))
        // Jornada de hoje: o próprio dia.
        assertEquals(ReportMath.today(t(14, 10), zone), ReportMath.workDay(t(14, 8), t(14, 10), zone))
    }

    @Test fun ganhos_por_dia_da_semana_comecam_na_segunda() {
        val byDay = ReportMath.incomeByWeekday(
            listOf(income(10.0, t(9, 12)), income(5.0, t(15, 12)), expense(99.0, t(9, 12))),
            zone,
        )
        assertEquals(7, byDay.size)
        assertEquals(10.0, byDay[0], 1e-9) // segunda
        assertEquals(5.0, byDay[6], 1e-9) // domingo
        assertEquals(0.0, byDay[1], 1e-9)
    }

    @Test fun ganhos_por_faixa_horaria() {
        val buckets = ReportMath.incomeByHour(
            listOf(income(10.0, t(9, 7, 30)), income(20.0, t(9, 8, 59)), income(5.0, t(9, 23))),
            bucketHours = 3,
            zone = zone,
        )
        assertEquals(8, buckets.size)
        assertEquals(6, buckets[2].startHour)
        assertEquals(9, buckets[2].endHour)
        assertEquals(30.0, buckets[2].total, 1e-9)
        assertEquals(5.0, buckets[7].total, 1e-9)
    }

    @Test fun melhores_horarios_por_rs_km_ponderado() {
        val offers = listOf(
            offer(30.0, 10.0, 20, t(9, 18)), // 18h: (30+10)/(10+10) = 2,0
            offer(10.0, 10.0, 20, t(10, 18, 30)),
            offer(25.0, 10.0, 20, t(9, 7)), // 7h: 2,5
            offer(99.0, null, 20, t(9, 12)), // sem km: ignorada
            offer(9.0, 10.0, 20, t(9, 22)), // 22h: 0,9
        )
        val best = ReportMath.bestHours(offers, top = 2, zone = zone)
        assertEquals(listOf(7, 18), best.map { it.hour })
        assertEquals(2.5, best[0].avgPerKm, 1e-9)
        assertEquals(2.0, best[1].avgPerKm, 1e-9)
        assertEquals(2, best[1].offers)

        val minTwo = ReportMath.bestHours(offers, minOffers = 2, zone = zone)
        assertEquals(listOf(18), minTwo.map { it.hour })
    }

    @Test fun contagem_por_semaforo_e_medias_das_ofertas() {
        val offers = listOf(
            offer(20.0, 10.0, 30, t(9, 8), Signal.GREEN),
            offer(10.0, 10.0, 30, t(9, 9), Signal.RED),
            offer(5.0, null, null, t(9, 10), Signal.RED),
        )
        val s = ReportMath.summarizeOffers(offers)
        assertEquals(3, s.count)
        assertEquals(1.5, s.avgPerKm!!, 1e-9)
        assertEquals(30.0, s.avgPerHour!!, 1e-9)
        assertEquals(1, s.signals[Signal.GREEN])
        assertEquals(0, s.signals[Signal.YELLOW])
        assertEquals(2, s.signals[Signal.RED])
    }

    @Test fun progresso_de_meta() {
        assertNull(ReportMath.goalProgress(100.0, null))
        assertNull(ReportMath.goalProgress(100.0, 0.0))
        val half = ReportMath.goalProgress(150.0, 300.0)!!
        assertEquals(0.5, half.fraction, 1e-9)
        assertEquals(150.0, half.remaining, 1e-9)
        assertFalse(half.reached)
        val over = ReportMath.goalProgress(360.0, 300.0)!!
        assertEquals(1.0, over.fraction, 1e-9)
        assertEquals(120, over.percent)
        assertEquals(0.0, over.remaining, 1e-9)
        assertTrue(over.reached)
    }

    @Test fun formatacao_pt_br() {
        assertEquals("R$ 1.234,56", Format.brl(1234.56))
        assertEquals("R$ 0,00", Format.brl(0.0))
        assertEquals("-R$ 12,50", Format.brl(-12.5))
        assertEquals("12,3 km", Format.km(12.34))
        assertEquals("R$ 2,15/km", Format.perKm(2.15))
        assertEquals("R$ 45,00/h", Format.perHour(45.0))
        assertEquals("R$ 0,083/km", Format.costPerKm(1.0 / 12))
        assertEquals("R$ 0,075/km", Format.costPerKm(0.9 / 12))
        assertEquals("—", Format.perKm(null))
        assertEquals("6,5", Format.input(6.5))
        assertEquals("", Format.input(null))
    }

    @Test fun leitura_de_numeros_digitados() {
        assertEquals(1234.56, Format.parse("1.234,56")!!, 1e-9)
        assertEquals(6.5, Format.parse("6,5")!!, 1e-9)
        assertEquals(6.5, Format.parse("6.5")!!, 1e-9)
        assertEquals(10.0, Format.parse("R$ 10")!!, 1e-9)
        assertNull(Format.parse(""))
        assertNull(Format.parse("abc"))
    }

    @Test fun semana_da_uber_vai_de_segunda_4h_a_segunda_4h() {
        // Sexta 13/11 15h: semana de seg 09/11 4h até seg 16/11 4h.
        val w = ReportMath.uberWeek(t(13, 15), zone)
        assertEquals(t(9, 4), w.from)
        assertEquals(t(16, 4) - 1, w.to)
        // Segunda antes das 4h ainda é a semana anterior; às 4h começa a nova.
        assertEquals(t(9, 4), ReportMath.uberWeek(t(16, 3, 59), zone).from)
        assertEquals(t(16, 4), ReportMath.uberWeek(t(16, 4), zone).from)
        // Domingo à noite e segunda de manhã cedo na mesma semana.
        assertEquals(t(9, 4), ReportMath.uberWeek(t(15, 23), zone).from)
    }

    @Test fun dia_do_calendario() {
        val d = ReportMath.dayOf(t(12, 15, 30), zone)
        assertEquals(t(12, 0), d.from)
        assertEquals(t(13, 0) - 1, d.to)
        assertEquals(d, ReportMath.day(java.time.LocalDate.of(2026, 11, 12), zone))
        assertEquals(ReportMath.today(t(12, 1), zone), d)
    }
}
