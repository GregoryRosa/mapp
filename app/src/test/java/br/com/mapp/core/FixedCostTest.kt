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
 * são descontados a cada corrida finalizada.
 */
class FixedCostTest {
    private val eps = 1e-6
    private val h = 3_600_000L

    /** 200 km/dia × 25 dias = 5.000 km/mês; 20 corridas/dia × 25 = 500 corridas/mês. */
    private val base = KmBase(kmPerDay = 200.0, daysPerMonth = 25.0, auto = false, ridesPerDay = 20.0)

    /**
     * Desgaste: gasolina 6,00/12 = 0,50 + manutenção 0,15 + depreciação 0,20 + pneus 250/mês ÷ 5.000 = 0,05
     * → 0,90/km. Fixos: seguro 300 + financiamento 1.200 = 1.500/mês ÷ 500 corridas = R$ 3,00 por corrida.
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

    @Test fun fixo_nao_entra_no_rs_por_km_e_vira_rs_por_corrida() {
        assertEquals(500.0, base.monthRides, eps)
        assertEquals(0.90, cfg.costPerKm, eps)
        assertEquals(1500.0, cfg.fixedMonthTotal, eps)
        assertEquals(3.0, cfg.fixedPerRide, eps)
        assertEquals(0.6, cfg.perRideOf(cfg.extraCosts[1]), eps)
        assertEquals(0.0, cfg.perKmOf(cfg.extraCosts[1]), eps)
        assertEquals(0.0, cfg.perRideOf(cfg.extraCosts[0]), eps)
        assertTrue(cfg.hasFixed)
        assertFalse(VehicleCostConfig().hasFixed)
        // Corrida de 10 km: 9,00 de desgaste + 3,00 de fixos.
        assertEquals(12.0, cfg.rideCost(10.0), eps)
        // Sem corridas na base o fixo não vira R$ por corrida (nada de dividir por zero).
        assertEquals(0.0, cfg.copy(kmBase = base.copy(ridesPerDay = 0.0)).fixedPerRide, eps)
        assertEquals(1500.0, base.perMonth(1500.0, CostUnit.FIXED_MONTH), eps)
    }

    @Test fun lucro_da_oferta_desconta_o_desgaste_e_a_parte_dos_fixos() {
        val raw = RawOffer(Platform.UBER, 20.0, tripKm = 8.0, tripMin = 16, pickupKm = 2.0, pickupMin = 4)
        val m = OfferEvaluator.metrics(raw, cfg)
        assertEquals(10.0, m.totalKm!!, eps)
        assertEquals(20.0 - 9.0 - 3.0, m.netProfit!!, eps)
        assertEquals(0.8, m.netPerKm!!, eps)
    }

    @Test fun divisao_por_item_tem_o_rs_por_km_e_o_rs_por_corrida() {
        val parts = CostSplit.parts(cfg)
        assertEquals(listOf("Combustível", "Manutenção", "Depreciação", "Pneus", "Seguro", "Financiamento"), parts.map { it.name })
        assertEquals(cfg.costPerKm, parts.sumOf { it.perKm }, eps)
        assertEquals(cfg.fixedPerRide, parts.sumOf { it.perRide }, eps)
        val fixed = CostSplit.splitFixed(30.0, parts) // 10 corridas × 3,00
        assertEquals(listOf("Seguro", "Financiamento"), fixed.map { it.name })
        assertEquals(6.0, fixed[0].amount, eps)
        assertEquals(24.0, fixed[1].amount, eps)
        assertEquals(listOf("Custo do carro"), CostSplit.splitFixed(5.0, listOf(CostPart("Pneus", 0.05))).map { it.name })
        // Gravação: ida e volta com o R$ por corrida; item antigo (2 valores) = só desgaste.
        assertEquals(parts.map { it.name to it.perRide }, CostSplit.decode(CostSplit.encode(parts))!!.map { it.name to it.perRide })
        assertEquals(listOf(CostPart("Seguro carro", 0.07)), CostSplit.decode("Seguro carro\u001D0.07"))
    }

    @Test fun jornada_desgaste_a_cada_km_e_fixo_a_cada_corrida_finalizada() {
        val shift = Shift(
            startedAt = 0, gpsKm = 50.0,
            trips = listOf(
                ShiftTrip(h, Platform.UBER, "UberX", 20.0, 1.0, 8.0),
                ShiftTrip(2 * h, Platform.UBER, "UberX", 15.0, 1.0, 5.0),
                ShiftTrip(3 * h, Platform.UBER, "UberX", 12.0, 1.0, 4.0, finished = false), // em andamento
            ),
        )
        val s = ShiftMath.summary(shift, cfg.costPerKm, 4 * h, cfg.fixedPerRide)
        assertEquals(45.0 + 6.0, s.cost, eps) // 50 km × 0,90 + 2 corridas finalizadas × 3,00
        assertEquals(6.0, s.fixedCost, eps)
        assertEquals(35.0 - 51.0, s.profit, eps)
        // Finalizou a terceira: desconta mais um fixo (os km já estavam no GPS).
        assertEquals(54.0, ShiftMath.summary(shift.finishPending(), cfg.costPerKm, 4 * h, cfg.fixedPerRide).cost, eps)
        // Cancelada nunca desconta.
        assertEquals(51.0, ShiftMath.summary(shift.cancelPending(), cfg.costPerKm, 4 * h, cfg.fixedPerRide).cost, eps)

        // Encerrada guarda o fixo da época; mudar o de agora não mexe nela.
        val ended = shift.copy(costPerKm = 0.9, fixedPerRide = 2.0, endedAt = 4 * h)
        assertEquals(45.0 + 4.0, ShiftMath.summary(ended, 1.5, 4 * h, 9.0).cost, eps)
        // Encerrada antes dos custos fixos por corrida: o custo/km dela já incluía tudo.
        val legacy = shift.copy(costPerKm = 1.0, endedAt = 4 * h)
        assertEquals(50.0, ShiftMath.summary(legacy, 0.9, 4 * h, 3.0).cost, eps)
        // Gravação da jornada leva o fixo por corrida.
        assertEquals(2.0, ShiftCodec.decode(ShiftCodec.encode(ended))!!.fixedPerRide!!, eps)
        assertNull(ShiftCodec.decode("v1\t1000\t5\t\t12.5\t0.9\t2000")!!.fixedPerRide)
    }

    @Test fun custos_de_hoje_mudaram_jornada_de_hoje_passa_a_usar_o_fixo_novo() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val now = LocalDate.of(2026, 11, 24).atTime(16, 0).atZone(zone).toInstant().toEpochMilli()
        val hoje = Shift(startedAt = now - 8 * h, gpsKm = 100.0, costPerKm = 1.0, endedAt = now - h,
            trips = listOf(ShiftTrip(now - 7 * h, Platform.UBER, null, 30.0, 1.0, 9.0)))
        val out = ShiftMath.withCostToday(listOf(hoje), cfg.costPerKm, now, zone, CostSplit.parts(cfg), cfg.fixedPerRide)
        assertEquals(3.0, out[0].fixedPerRide!!, eps)
        assertEquals(90.0 + 3.0, ShiftMath.summary(out[0], 0.0, now).cost, eps)
    }

    @Test fun periodo_soma_fixo_das_corridas_da_jornada_e_das_de_fora_e_divide_por_item() {
        val parts = CostSplit.parts(cfg)
        val shift = Shift(
            startedAt = 8 * h, activeMs = 4 * h, resumedAt = null, gpsKm = 100.0, endedAt = 12 * h,
            costPerKm = cfg.costPerKm, fixedPerRide = cfg.fixedPerRide, costParts = parts,
            trips = listOf(
                ShiftTrip(9 * h, Platform.UBER, "UberX", 40.0, 1.0, 10.0),
                ShiftTrip(10 * h, Platform.UBER, "UberX", 30.0, 1.0, 8.0),
            ),
        )
        // Corrida aceita fora da jornada (à noite): 10 km da oferta + o fixo dela.
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
        val t = PeriodMath.totals(0, 24 * h, null, listOf(shift), listOf(loose), emptyList(), cfg.costPerKm, 23 * h, parts, cfg.fixedPerRide)
        assertEquals(3, t.rides)
        assertEquals(9.0, t.fixedCost, eps)                   // 3 corridas × 3,00
        assertEquals(110.0 * 0.9, t.kmCost, eps)             // desgaste de 110 km
        assertEquals(99.0 + 9.0, t.carCost, eps)
        assertEquals(3.0, t.fixedPerRide!!, eps)
        assertEquals(0.9, t.kmCostPerKm!!, eps)
        assertEquals(t.carCost, t.carCostItems.sumOf { it.amount }, eps)
        assertEquals(1.8, t.carCostItems.first { it.name == "Seguro" }.amount, eps)
        assertEquals(7.2, t.carCostItems.first { it.name == "Financiamento" }.amount, eps)
        assertEquals(110.0 * 0.05, t.carCostItems.first { it.name == "Pneus" }.amount, eps)
        assertEquals(95.0 - 108.0, t.net, eps)
    }

    @Test fun media_das_jornadas_traz_corridas_por_dia() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val today = LocalDate.of(2026, 11, 24)
        fun at(daysAgo: Long, hour: Int) = today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        fun trips(n: Int, daysAgo: Long, finished: Boolean = true) =
            (0 until n).map { ShiftTrip(at(daysAgo, 9) + it * 60_000L, Platform.UBER, null, 10.0, 1.0, 3.0, finished) }
        val shifts = listOf(
            Shift(startedAt = at(1, 7), gpsKm = 120.0, trips = trips(14, 1)),
            Shift(startedAt = at(1, 18), gpsKm = 60.0, trips = trips(4, 1) + trips(1, 1, finished = false)),
            Shift(startedAt = at(2, 8), gpsKm = 150.0, trips = trips(22, 2)),
        )
        val avg = KmAverages.of(shifts, at(0, 20), zone)!!
        assertEquals(20.0, avg.ridesPerDay!!, eps) // (18 + 22) / 2 dias; em andamento não conta
        assertEquals(20.0, KmAverages.applied(KmBase(ridesPerDay = 12.0), avg).ridesPerDay, eps)
        // Sem corridas contadas fica o que estava; base digitada não muda.
        val semCorridas = KmAverages.of(listOf(Shift(startedAt = at(1, 8), gpsKm = 100.0)), at(0, 20), zone)!!
        assertNull(semCorridas.ridesPerDay)
        assertEquals(12.0, KmAverages.applied(KmBase(ridesPerDay = 12.0), semCorridas).ridesPerDay, eps)
        assertEquals(15.0, KmAverages.applied(KmBase(ridesPerDay = 15.0, auto = false), avg).ridesPerDay, eps)
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
