package br.com.mapp.core

import br.com.mapp.core.engine.OfferEvaluator
import br.com.mapp.core.model.CostUnit
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.ExtraKmCost
import br.com.mapp.core.model.ExtraKmCostCodec
import br.com.mapp.core.model.FuelType
import br.com.mapp.core.model.KmBase
import br.com.mapp.core.model.OfferMetrics
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.Signal
import br.com.mapp.core.model.VehicleCostConfig
import br.com.mapp.core.repo.OfferRecord
import br.com.mapp.core.shift.CostPart
import br.com.mapp.core.shift.CostSplit
import br.com.mapp.core.shift.KmAverages
import br.com.mapp.core.shift.PeriodMath
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftCodec
import br.com.mapp.core.shift.ShiftMath
import br.com.mapp.core.shift.ShiftTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Desgaste (combustível, pneus, manutenção) conta a cada km; custos fixos (seguro, financiamento)
 * são descontados como % do valor de cada corrida finalizada.
 */
class FixedCostTest {
    private val eps = 1e-6
    private val h = 3_600_000L

    /** 200 km/dia × 25 dias = 5.000 km/mês; R$ 240/dia × 25 = R$ 6.000 de faturamento no mês. */
    private val base = KmBase(kmPerDay = 200.0, daysPerMonth = 25.0, auto = false, grossPerDay = 240.0)

    /**
     * Desgaste: gasolina 6,00/12 = 0,50 + manutenção 0,15 + depreciação 0,20 + pneus 250/mês ÷ 5.000 = 0,05
     * → 0,90/km. Fixos: seguro 300 + financiamento 1.200 = 1.500/mês ÷ R$ 6.000 = 25% do valor.
     */
    private val cfg = VehicleCostConfig(
        fuelType = FuelType.GASOLINE,
        fuelPricePerUnit = 6.0,
        kmPerUnit = 12.0,
        maintenance = 0.15,
        depreciation = 0.20,
        extraCosts = listOf(
            ExtraKmCost("Pneus", 250.0, CostUnit.PER_MONTH),
            ExtraKmCost("Seguro", 300.0, CostUnit.FIXED_MONTH),
            ExtraKmCost("Financiamento", 1200.0, CostUnit.FIXED_MONTH),
        ),
        kmBase = base,
    )

    @Test fun fixo_nao_entra_no_rs_por_km_e_vira_porcentagem_do_valor() {
        assertEquals(6000.0, base.monthGross, eps)
        assertEquals(0.90, cfg.costPerKm, eps)
        assertEquals(1500.0, cfg.fixedMonthTotal, eps)
        assertEquals(0.25, cfg.fixedShare, eps)
        assertEquals(0.05, cfg.shareOf(cfg.extraCosts[1]), eps)
        assertEquals(0.0, cfg.perKmOf(cfg.extraCosts[1]), eps)
        assertEquals(0.0, cfg.shareOf(cfg.extraCosts[0]), eps)
        assertEquals(0.0, base.share(1500.0, CostUnit.PER_MONTH), eps)
        assertTrue(cfg.hasFixed)
        assertFalse(VehicleCostConfig().hasFixed)
        // Corrida de 10 km e R$ 20: 9,00 de desgaste + 25% de R$ 20 = 5,00 de fixos.
        assertEquals(5.0, cfg.fixedCostOf(20.0), eps)
        assertEquals(14.0, cfg.rideCost(10.0, 20.0), eps)
        // Sem faturamento na base o fixo não vira % (nada de dividir por zero).
        assertEquals(0.0, base.copy(grossPerDay = 0.0).monthGross, eps)
        assertEquals(0.0, cfg.copy(kmBase = base.copy(grossPerDay = 0.0)).fixedShare, eps)
        assertEquals(0.0, cfg.copy(kmBase = base.copy(daysPerMonth = 0.0)).fixedShare, eps)
        assertEquals(1500.0, base.perMonth(1500.0, CostUnit.FIXED_MONTH), eps)
    }

    @Test fun lucro_da_oferta_desconta_o_desgaste_e_a_porcentagem_dos_fixos() {
        val raw = RawOffer(Platform.UBER, 20.0, tripKm = 8.0, tripMin = 16, pickupKm = 2.0, pickupMin = 4)
        val m = OfferEvaluator.metrics(raw, cfg)
        assertEquals(10.0, m.totalKm!!, eps)
        assertEquals(20.0 - 9.0 - 5.0, m.netProfit!!, eps)
        assertEquals(0.6, m.netPerKm!!, eps)
        // Corrida curta de R$ 12 com 2 km paga R$ 3 de fixo (não o mesmo que a de R$ 50).
        val curta = RawOffer(Platform.UBER, 12.0, tripKm = 1.5, tripMin = 5, pickupKm = 0.5, pickupMin = 2)
        assertEquals(12.0 - 1.8 - 3.0, OfferEvaluator.metrics(curta, cfg).netProfit!!, eps)
    }

    @Test fun divisao_por_item_tem_o_rs_por_km_e_a_porcentagem() {
        val parts = CostSplit.parts(cfg)
        assertEquals(listOf("Combustível", "Manutenção", "Depreciação", "Pneus", "Seguro", "Financiamento"), parts.map { it.name })
        assertEquals(cfg.costPerKm, parts.sumOf { it.perKm }, eps)
        assertEquals(cfg.fixedShare, parts.sumOf { it.fixedShare }, eps)
        val fixed = CostSplit.splitFixed(30.0, parts) // 25% de R$ 120
        assertEquals(listOf("Seguro", "Financiamento"), fixed.map { it.name })
        assertEquals(6.0, fixed[0].amount, eps)
        assertEquals(24.0, fixed[1].amount, eps)
        assertEquals(listOf("Custo do carro"), CostSplit.splitFixed(5.0, listOf(CostPart("Pneus", 0.05))).map { it.name })
        // Gravação: ida e volta com a %; item antigo (2 valores) = só desgaste.
        assertEquals(parts.map { it.name to it.fixedShare }, CostSplit.decode(CostSplit.encode(parts))!!.map { it.name to it.fixedShare })
        assertEquals(listOf(CostPart("Seguro carro", 0.07)), CostSplit.decode("Seguro carro\u001D0.07"))
        // Jornada gravada com R$ por corrida (0,60 e 2,40): o valor serve de peso igual.
        val old = CostSplit.decode("Seguro\u001D0.0\u001D0.6\u001FFinanciamento\u001D0.0\u001D2.4")!!
        assertEquals(listOf(6.0, 24.0), CostSplit.splitFixed(30.0, old).map { it.amount })
    }

    @Test fun jornada_desgaste_a_cada_km_e_fixo_pelo_valor_de_cada_corrida_finalizada() {
        val shift = Shift(
            startedAt = 0, gpsKm = 50.0,
            trips = listOf(
                ShiftTrip(h, Platform.UBER, "UberX", 20.0, 1.0, 8.0),
                ShiftTrip(2 * h, Platform.UBER, "UberX", 15.0, 1.0, 5.0),
                ShiftTrip(3 * h, Platform.UBER, "UberX", 12.0, 1.0, 4.0, finished = false), // em andamento
            ),
        )
        assertEquals(35.0, shift.finishedGross, eps)
        val s = ShiftMath.summary(shift, cfg.costPerKm, 4 * h, cfg.fixedShare)
        assertEquals(45.0 + 8.75, s.cost, eps) // 50 km × 0,90 + 25% de R$ 35
        assertEquals(8.75, s.fixedCost, eps)
        assertEquals(35.0 - 53.75, s.profit, eps)
        // Finalizou a terceira: desconta 25% dos R$ 12 dela (os km já estavam no GPS).
        assertEquals(56.75, ShiftMath.summary(shift.finishPending(), cfg.costPerKm, 4 * h, cfg.fixedShare).cost, eps)
        // Cancelada nunca desconta.
        assertEquals(53.75, ShiftMath.summary(shift.cancelPending(), cfg.costPerKm, 4 * h, cfg.fixedShare).cost, eps)

        // Encerrada guarda a % da época; mudar a de agora não mexe nela.
        val ended = shift.copy(costPerKm = 0.9, fixedShare = 0.2, endedAt = 4 * h)
        assertEquals(45.0 + 7.0, ShiftMath.summary(ended, 1.5, 4 * h, 0.9).cost, eps)
        // Encerrada por versão anterior com R$ por corrida: continua corridas finalizadas × valor.
        val perRide = shift.copy(costPerKm = 0.9, fixedPerRide = 2.0, endedAt = 4 * h)
        assertEquals(45.0 + 4.0, ShiftMath.summary(perRide, 1.5, 4 * h, 0.9).cost, eps)
        // Encerrada antes dos custos fixos: o custo/km dela já incluía tudo.
        val legacy = shift.copy(costPerKm = 1.0, endedAt = 4 * h)
        assertEquals(50.0, ShiftMath.summary(legacy, 0.9, 4 * h, 0.25).cost, eps)
        // Gravação da jornada leva a %; o R$ por corrida antigo continua sendo lido.
        val back = ShiftCodec.decode(ShiftCodec.encode(ended))!!
        assertEquals(0.2, back.fixedShare!!, eps)
        assertNull(back.fixedPerRide)
        val backOld = ShiftCodec.decode(ShiftCodec.encode(perRide))!!
        assertEquals(2.0, backOld.fixedPerRide!!, eps)
        assertNull(backOld.fixedShare)
    }

    @Test fun codec_le_jornada_gravada_antes_da_porcentagem() {
        // Sem os campos do fixo (versão antiga).
        val older = ShiftCodec.decode("v1\t1000\t5\t\t12.5\t0.9\t2000")!!
        assertNull(older.fixedPerRide)
        assertNull(older.fixedShare)
        // Com o R$ por corrida e sem o campo da %: soma corridas finalizadas × R$ 2,50.
        val text = "v1\t1000\t5\t\t12.5\t0.9\t2000\t\t2.5\n" +
            "1500\tUBER\tUberX\t20.0\t1.0\t5.0\t\n" +
            "1600\tUBER\tUberX\t30.0\t1.0\t8.0\t"
        val shift = ShiftCodec.decode(text)!!
        assertEquals(2.5, shift.fixedPerRide!!, eps)
        assertNull(shift.fixedShare)
        assertEquals(2, shift.trips.size)
        assertEquals(5.0, ShiftMath.summary(shift, 0.0, 3000, 0.25).fixedCost, eps)
    }

    @Test fun custos_de_hoje_mudaram_jornada_de_hoje_passa_a_usar_a_porcentagem_nova() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val now = LocalDate.of(2026, 11, 24).atTime(16, 0).atZone(zone).toInstant().toEpochMilli()
        val hoje = Shift(startedAt = now - 8 * h, gpsKm = 100.0, costPerKm = 1.0, endedAt = now - h, fixedPerRide = 3.0,
            trips = listOf(ShiftTrip(now - 7 * h, Platform.UBER, null, 30.0, 1.0, 9.0)))
        val out = ShiftMath.withCostToday(listOf(hoje), cfg.costPerKm, now, zone, CostSplit.parts(cfg), cfg.fixedShare)
        assertEquals(0.25, out[0].fixedShare!!, eps)
        assertNull(out[0].fixedPerRide)
        assertEquals(90.0 + 7.5, ShiftMath.summary(out[0], 0.0, now).cost, eps)
    }

    @Test fun periodo_soma_fixo_das_corridas_da_jornada_e_divide_por_item() {
        val parts = CostSplit.parts(cfg)
        val shift = Shift(
            startedAt = 8 * h, activeMs = 4 * h, resumedAt = null, gpsKm = 100.0, endedAt = 12 * h,
            costPerKm = cfg.costPerKm, fixedShare = cfg.fixedShare, costParts = parts,
            trips = listOf(
                ShiftTrip(9 * h, Platform.UBER, "UberX", 40.0, 1.0, 10.0),
                ShiftTrip(10 * h, Platform.UBER, "UberX", 30.0, 1.0, 8.0),
            ),
        )
        // Corrida aceita fora da jornada (à noite): 10 km da oferta; fixo não (o rateio do mês é só das jornadas).
        val loose = OfferRecord(
            id = 0,
            evaluated = EvaluatedOffer(
                raw = RawOffer(Platform.UBER, 25.0, tripKm = 8.0, capturedAt = 20 * h),
                metrics = OfferMetrics(10.0, 20, null, null, null, null),
                signal = Signal.GREEN,
                reasons = emptyList(),
            ),
            accepted = true,
        )
        val t = PeriodMath.totals(0, 24 * h, null, listOf(shift), listOf(loose), emptyList(), cfg.costPerKm, 23 * h, parts, cfg.fixedShare)
        assertEquals(3, t.rides)
        assertEquals(17.5, t.fixedCost, eps)                 // 25% de R$ 70 das corridas da jornada
        assertEquals(110.0 * 0.9, t.kmCost, eps)             // desgaste de 110 km
        assertEquals(99.0 + 17.5, t.carCost, eps)
        assertEquals(0.25, t.fixedShare!!, eps)              // % das corridas das jornadas (a de fora não paga)
        assertEquals(0.9, t.kmCostPerKm!!, eps)
        assertEquals(t.carCost, t.carCostItems.sumOf { it.amount }, eps)
        assertEquals(3.5, t.carCostItems.first { it.name == "Seguro" }.amount, eps)
        assertEquals(14.0, t.carCostItems.first { it.name == "Financiamento" }.amount, eps)
        assertEquals(110.0 * 0.05, t.carCostItems.first { it.name == "Pneus" }.amount, eps)
        assertEquals(95.0 - 116.5, t.net, eps)
        // Jornada aberta usa a % do dia; sem custo fixo, sem %.
        val open = shift.copy(costPerKm = null, fixedShare = null, endedAt = null, resumedAt = 12 * h)
        assertEquals(0.3 * 70.0, PeriodMath.totals(0, 24 * h, open, emptyList(), emptyList(), emptyList(), 0.9, 13 * h, parts, 0.3).fixedCost, eps)
        assertNull(PeriodMath.totals(0, 24 * h, open, emptyList(), emptyList(), emptyList(), 0.9, 13 * h).fixedShare)
    }

    @Test fun media_das_jornadas_traz_o_bruto_por_dia() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val today = LocalDate.of(2026, 11, 24)
        fun at(daysAgo: Long, hour: Int) = today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        fun trips(n: Int, daysAgo: Long, finished: Boolean = true, price: Double = 10.0) =
            (0 until n).map { ShiftTrip(at(daysAgo, 9) + it * 60_000L, Platform.UBER, null, price, 1.0, 3.0, finished) }
        val shifts = listOf(
            Shift(startedAt = at(1, 7), gpsKm = 120.0, trips = trips(14, 1)),
            Shift(startedAt = at(1, 18), gpsKm = 60.0, trips = trips(4, 1) + trips(1, 1, finished = false)),
            Shift(startedAt = at(2, 8), gpsKm = 150.0, trips = trips(22, 2)),
        )
        val avg = KmAverages.of(shifts, at(0, 20), zone)!!
        assertEquals(200.0, avg.grossPerDay!!, eps) // (R$ 180 + R$ 220) / 2 dias; em andamento não conta
        assertEquals(200.0, KmAverages.applied(KmBase(grossPerDay = 120.0), avg).grossPerDay, eps)
        // Arredonda para R$ inteiro.
        val round = KmAverages.of(listOf(Shift(startedAt = at(1, 8), gpsKm = 50.0, trips = trips(3, 1, price = 33.4))), at(0, 20), zone)!!
        assertEquals(100.0, round.grossPerDay!!, eps)
        // Sem corridas contadas fica o que estava; base digitada não muda.
        val semCorridas = KmAverages.of(listOf(Shift(startedAt = at(1, 8), gpsKm = 100.0)), at(0, 20), zone)!!
        assertNull(semCorridas.grossPerDay)
        assertEquals(120.0, KmAverages.applied(KmBase(grossPerDay = 120.0), semCorridas).grossPerDay, eps)
        assertEquals(150.0, KmAverages.applied(KmBase(grossPerDay = 150.0, auto = false), avg).grossPerDay, eps)
    }

    @Test fun jornada_de_hoje_so_entra_na_media_amanha() {
        // 26/11: o "M" mostrava lucro de R$ 84 e, ao encerrar a jornada, o faturamento do dia entrava
        // na média, a % dos fixos caía e o lucro do mesmo dia virava R$ 100.
        val zone = ZoneId.of("America/Sao_Paulo")
        val today = LocalDate.of(2026, 11, 26)
        fun at(daysAgo: Long, hour: Int) = today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        fun trips(n: Int, daysAgo: Long, price: Double) =
            (0 until n).map { ShiftTrip(at(daysAgo, 9) + it * 60_000L, Platform.UBER, null, price, 1.0, 3.0, true) }
        val past = listOf(
            Shift(startedAt = at(1, 8), gpsKm = 100.0, trips = trips(10, 1, 20.0)),
            Shift(startedAt = at(2, 8), gpsKm = 140.0, trips = trips(10, 2, 20.0)),
        )
        val hoje = Shift(startedAt = at(0, 14), gpsKm = 180.0, trips = trips(15, 0, 20.0))
        val antes = KmAverages.before(past, today, zone)!!
        val depois = KmAverages.before(past + hoje, today, zone)!!
        // Encerrar a jornada de hoje não muda a média do dia (nem a % dos fixos que sai dela).
        assertEquals(antes, depois)
        assertEquals(120.0, depois.kmPerDay, eps)
        assertEquals(200.0, depois.grossPerDay!!, eps)
        // No dia seguinte ela entra.
        val amanha = KmAverages.before(past + hoje, today.plusDays(1), zone)!!
        assertEquals(140.0, amanha.kmPerDay, eps)           // (100 + 140 + 180) / 3
        assertEquals(233.0, amanha.grossPerDay!!, eps)      // (200 + 200 + 300) / 3
        // Sem dia anterior com jornada: nada a aplicar (fica a base que estava).
        assertNull(KmAverages.before(listOf(hoje), today, zone))
    }

    @Test fun custo_do_mes_com_nome_de_fixo_vira_fixo_uma_vez_so() {
        val old = listOf(
            "v2\tSeguro do carro\t250\tPER_MONTH",
            "v2\tParcela do financiamento\t1200\tPER_MONTH",
            "v2\tIPVA\t0.03\tPER_KM",
            "v2\tPneus\t100\tPER_MONTH",
        ).joinToString("\n")
        val decoded = ExtraKmCostCodec.decode(old)
        assertEquals(
            listOf(CostUnit.FIXED_MONTH, CostUnit.FIXED_MONTH, CostUnit.PER_KM, CostUnit.PER_MONTH),
            decoded.map { it.unit },
        )
        // Gravado de novo ("v3"), vale o que o motorista escolher, mesmo com nome de fixo.
        val chosen = listOf(ExtraKmCost("Seguro", 250.0, CostUnit.PER_MONTH), ExtraKmCost("Lavagem", 80.0, CostUnit.FIXED_MONTH))
        assertEquals(chosen, ExtraKmCostCodec.decode(ExtraKmCostCodec.encode(chosen)))
        assertTrue(ExtraKmCostCodec.looksFixed("Prestação do Consórcio"))
        assertFalse(ExtraKmCostCodec.looksFixed("Troca de óleo"))
    }
}
