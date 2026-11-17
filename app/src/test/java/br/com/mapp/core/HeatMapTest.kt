package br.com.mapp.core

import br.com.mapp.core.heat.HeatCell
import br.com.mapp.core.heat.HeatFilter
import br.com.mapp.core.heat.HeatGrid
import br.com.mapp.core.heat.HeatHours
import br.com.mapp.core.heat.HeatMap
import br.com.mapp.core.heat.HeatMetric
import br.com.mapp.core.heat.HeatPalette
import br.com.mapp.core.heat.HeatPeriod
import br.com.mapp.core.heat.HeatPlane
import br.com.mapp.core.heat.HeatPoint
import br.com.mapp.core.heat.HeatPoints
import br.com.mapp.core.heat.HeatScale
import br.com.mapp.core.heat.HeatSet
import br.com.mapp.core.heat.HeatSpot
import br.com.mapp.core.heat.HeatZones
import br.com.mapp.core.heat.HexMath
import br.com.mapp.core.heat.PlanePoint
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.OfferMetrics
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.Signal
import br.com.mapp.core.model.ZoneType
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mapa de calor das zonas: grade de hexágonos, suavização, escala de cor e zonas automáticas. */
class HeatMapTest {

    private val sp = GeoPoint(-23.5505, -46.6333)
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val plane = HeatPlane(sp.lat, sp.lon)
    private val size = 300.0

    private fun point(
        lat: Double = sp.lat,
        lon: Double = sp.lon,
        price: Double = 20.0,
        perKm: Double? = 2.0,
        perHour: Double? = 40.0,
        profit: Double? = 12.0,
        platform: Platform = Platform.UBER,
        category: String? = "UberX",
        at: Long = 0L,
        accepted: Boolean = false,
    ) = HeatPoint(GeoPoint(lat, lon), price, perKm, perHour, profit, platform, category, at, accepted)

    /** Oferta na posição do hexágono (q, r) da grade de teste. */
    private fun pointAt(q: Int, r: Int, perKm: Double? = 2.0, category: String? = "UberX", accepted: Boolean = false) =
        point(
            lat = plane.geo(HexMath.center(q, r, size)).lat,
            lon = plane.geo(HexMath.center(q, r, size)).lon,
            perKm = perKm,
            category = category,
            accepted = accepted,
        )

    private fun offer(
        pickup: GeoPoint? = null,
        dropoff: GeoPoint? = null,
        price: Double = 20.0,
        category: String? = "UberX",
    ) = EvaluatedOffer(
        raw = RawOffer(platform = Platform.UBER, price = price, category = category, capturedAt = 1_000L),
        metrics = OfferMetrics(totalKm = 10.0, totalMin = 20, perKm = 2.0, perHour = 60.0, netProfit = 12.0, netPerKm = 1.2),
        signal = Signal.GREEN,
        reasons = emptyList(),
        pickup = pickup,
        dropoff = dropoff,
    )

    // --- oferta → ponto do mapa -------------------------------------------------------------

    @Test fun oferta_sem_coordenada_fica_fora_do_mapa() {
        assertNull(HeatPoints.of(offer(), accepted = true, spot = HeatSpot.PICKUP))
        assertNull(HeatPoints.of(offer(pickup = sp), accepted = true, spot = HeatSpot.DROPOFF))
        assertNull(HeatPoints.of(offer(pickup = GeoPoint(0.0, 0.0)), accepted = null, spot = HeatSpot.PICKUP))
        val ok = HeatPoints.of(offer(pickup = sp), accepted = true, spot = HeatSpot.PICKUP)
        assertNotNull(ok)
        assertEquals(sp, ok!!.point)
        assertTrue(ok.accepted)
        assertEquals("UberX", ok.category)
    }

    @Test fun ponto_do_destino_usa_a_coordenada_do_destino() {
        val rio = GeoPoint(-22.9, -43.2)
        val p = HeatPoints.of(offer(pickup = sp, dropoff = rio), accepted = false, spot = HeatSpot.DROPOFF)
        assertEquals(rio, p?.point)
        assertFalse(p!!.accepted)
    }

    // --- filtros ----------------------------------------------------------------------------

    @Test fun filtro_de_periodo_horario_app_e_modalidade() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val points = listOf(
            point(at = now - 2 * day, platform = Platform.UBER, category = "UberX"),
            point(at = now - 50 * day, platform = Platform.UBER, category = "UberX"),
            point(at = now - 2 * day, platform = Platform.NINETY_NINE, category = "Pop"),
            point(at = now - 2 * day, platform = Platform.UBER, category = null, accepted = true),
        )
        val recent = HeatPoints.filter(points, HeatFilter(period = HeatPeriod.DAYS_7), now, zone)
        assertEquals(3, recent.size)
        assertEquals(4, HeatPoints.filter(points, HeatFilter(period = HeatPeriod.ALL), now, zone).size)

        val all = HeatPeriod.ALL
        val uber = HeatPoints.filter(points, HeatFilter(period = all, platforms = setOf(Platform.UBER)), now, zone)
        assertEquals(3, uber.size)

        val uberX = HeatPoints.filter(points, HeatFilter(period = all, categories = setOf("UberX")), now, zone)
        assertEquals(2, uberX.size)

        val accepted = HeatPoints.filter(points, HeatFilter(period = all, set = HeatSet.ACCEPTED), now, zone)
        assertEquals(1, accepted.size)

        // Os filtros somam: Uber dentro dos 30 dias deixa de fora a oferta de 50 dias atrás.
        val recentUber = HeatPoints.filter(
            points,
            HeatFilter(period = HeatPeriod.DAYS_30, platforms = setOf(Platform.UBER)),
            now,
            zone,
        )
        assertEquals(2, recentUber.size)
    }

    @Test fun faixa_de_horario_vira_a_meia_noite() {
        val night = HeatHours(22, 4)
        assertTrue(night.contains(22))
        assertTrue(night.contains(23))
        assertTrue(night.contains(0))
        assertTrue(night.contains(3))
        assertFalse(night.contains(4))
        assertFalse(night.contains(12))

        val afternoon = HeatHours(12, 18)
        assertTrue(afternoon.contains(12))
        assertFalse(afternoon.contains(18))
        assertFalse(afternoon.contains(6))

        assertTrue(HeatHours(0, 0).isAll)
        assertTrue(HeatHours(0, 0).contains(5))
    }

    @Test fun modalidades_vem_da_mais_comum_para_a_menos() {
        val points = listOf(
            point(category = "Comfort"),
            point(category = "UberX"),
            point(category = "UberX"),
            point(category = null),
        )
        assertEquals(listOf("UberX", "Comfort"), HeatPoints.categories(points))
    }

    // --- grade de hexágonos -----------------------------------------------------------------

    @Test fun hexagono_tem_sempre_o_mesmo_tamanho_na_tela() {
        val lat = -23.55
        // Um zoom a mais = metade do chão, então o hexágono fica igual na tela.
        val z14 = HeatGrid.sizeMeters(14.0, lat)
        val z15 = HeatGrid.sizeMeters(15.0, lat)
        val z16 = HeatGrid.sizeMeters(16.0, lat)
        assertEquals(z14 / 2, z15, z15 * 0.001)
        assertEquals(z15 / 2, z16, z16 * 0.001)

        // E a largura dele na tela é sempre a mesma em dp.
        listOf(12.0, 14.0, 15.5, 16.0).forEach { z ->
            val widthDp = HeatGrid.sizeMeters(z, lat) * HexMath.SQRT3 / HeatGrid.metersPerDp(z, lat)
            assertEquals(HeatGrid.TARGET_DP, widthDp, 0.01)
        }
        // Com limites: nem fino demais no zoom máximo, nem gigante no mínimo.
        assertEquals(HeatGrid.MIN_SIZE_M, HeatGrid.sizeMeters(22.0, lat), 0.001)
        assertEquals(HeatGrid.MAX_SIZE_M, HeatGrid.sizeMeters(5.0, lat), 0.001)
    }

    @Test fun ponto_cai_no_hexagono_dele() {
        listOf(0 to 0, 3 to -2, -5 to 4, 10 to 10).forEach { (q, r) ->
            val center = HexMath.center(q, r, size)
            assertEquals(q to r, HexMath.cellOf(center, size))
            // Quase na borda, ainda é o mesmo hexágono.
            assertEquals(q to r, HexMath.cellOf(PlanePoint(center.x + size * 0.4, center.y), size))
        }
    }

    @Test fun hexagonos_vizinhos_dividem_dois_cantos() {
        val mine = HexMath.corners(0, 0, size)
        HexMath.NEIGHBORS.forEach { (dq, dr) ->
            val theirs = HexMath.corners(dq, dr, size)
            val shared = mine.count { a -> theirs.any { b -> abs(a.x - b.x) < 0.01 && abs(a.y - b.y) < 0.01 } }
            assertEquals(2, shared)
        }
        // O hexágono tem 6 cantos e a largura √3 · raio.
        assertEquals(6, mine.size)
        val width = mine.maxOf { it.x } - mine.minOf { it.x }
        assertEquals(size * sqrt(3.0), width, 0.001)
    }

    @Test fun oferta_sozinha_nao_pinta_e_a_dupla_pinta() {
        val alone = HeatGrid.build(listOf(pointAt(0, 0)), plane, size, HeatGrid.kernelMeters(size))
        assertTrue(alone.isEmpty)

        val pair = HeatGrid.build(List(2) { pointAt(0, 0) }, plane, size, HeatGrid.kernelMeters(size))
        assertFalse(pair.isEmpty)
        assertEquals(2, pair.plotted)
    }

    @Test fun a_mancha_passa_para_os_vizinhos() {
        val map = HeatGrid.build(List(4) { pointAt(0, 0) }, plane, size, HeatGrid.kernelMeters(size))
        // Mais de um hexágono pintado, mesmo com as ofertas todas no mesmo ponto: é a suavização.
        assertTrue(map.cells.size > 1)
        val middle = map.cells.first { it.q == 0 && it.r == 0 }
        assertEquals(4, middle.count)
        // O miolo é sempre o mais quente.
        assertTrue(map.cells.all { it.weight <= middle.weight + 1e-9 })
        // E a oferta conta num hexágono só.
        assertEquals(4, map.cells.sumOf { it.count })
    }

    @Test fun hexagono_resume_media_e_modalidade_mais_vista() {
        val points = listOf(
            pointAt(0, 0, perKm = 1.0, category = "UberX"),
            pointAt(0, 0, perKm = 3.0, category = "UberX"),
            pointAt(0, 0, perKm = null, category = "Comfort"),
        )
        val map = HeatGrid.build(points, plane, size, HeatGrid.kernelMeters(size))
        val middle = map.cells.first { it.q == 0 && it.r == 0 }
        assertEquals(2.0, middle.avgPerKm!!, 0.001)
        assertEquals(20.0, middle.avgPrice!!, 0.001)
        assertEquals("UberX", middle.topCategory)
        assertEquals(3, middle.count)
        assertEquals(6, middle.polygon.size)
    }

    @Test fun hexagono_sem_o_numero_da_metrica_nao_tem_valor() {
        val points = List(3) { pointAt(0, 0, perKm = null) }.map { it.copy(perHour = null, profit = null) }
        val map = HeatGrid.build(points, plane, size, HeatGrid.kernelMeters(size))
        val middle = map.cells.first { it.q == 0 && it.r == 0 }
        assertNull(middle.value(HeatMetric.PER_KM))
        assertNull(middle.value(HeatMetric.PER_HOUR))
        assertNull(middle.value(HeatMetric.PROFIT))
        assertTrue(middle.value(HeatMetric.CALLS)!! > 0.0)
        assertNull(HeatScale.of(map.cells, HeatMetric.PER_KM))
    }

    @Test fun peso_cai_com_a_distancia_e_zera_no_raio() {
        assertEquals(1.0, HeatGrid.weightOf(0.0, 500.0), 0.001)
        assertEquals(0.0, HeatGrid.weightOf(500.0, 500.0), 0.001)
        assertEquals(0.0, HeatGrid.weightOf(900.0, 500.0), 0.001)
        assertTrue(HeatGrid.weightOf(100.0, 500.0) > HeatGrid.weightOf(300.0, 500.0))
    }

    // --- escala de cor ----------------------------------------------------------------------

    @Test fun o_mais_cheio_fica_quente_e_o_mais_vazio_frio() {
        val cells = listOf(cell(0, 0, weight = 10.0), cell(1, 0, weight = 5.0), cell(2, 0, weight = 0.5))
        val scale = HeatScale.of(cells, HeatMetric.CALLS)!!
        assertEquals(1.0, scale.fraction(cells[0], HeatMetric.CALLS)!!, 0.001)
        assertTrue(scale.fraction(cells[2], HeatMetric.CALLS)!! < 0.2)
        assertTrue(scale.isHot(cells[0], HeatMetric.CALLS))
        assertFalse(scale.isHot(cells[2], HeatMetric.CALLS))
    }

    @Test fun dinheiro_usa_a_posicao_na_fila_e_o_melhor_e_sempre_o_mais_quente() {
        val cells = (0..9).map { cell(it, 0, perKm = 2.0 + it * 0.05) }
        val scale = HeatScale.of(cells, HeatMetric.PER_KM)!!
        assertEquals(1.0, scale.fraction(cells.last(), HeatMetric.PER_KM)!!, 0.001)
        assertEquals(0.0, scale.fraction(cells.first(), HeatMetric.PER_KM)!!, 0.001)
        assertTrue(scale.isHot(cells.last(), HeatMetric.PER_KM))
        assertFalse(scale.isHot(cells.first(), HeatMetric.PER_KM))
    }

    @Test fun quando_nada_se_destaca_fica_tudo_no_meio_e_sem_zona() {
        val cells = (0..4).map { cell(it * 3, 0, weight = 4.0) }
        val scale = HeatScale.of(cells, HeatMetric.CALLS)!!
        cells.forEach { assertEquals(0.5, scale.fraction(it, HeatMetric.CALLS)!!, 0.001) }
        assertTrue(HeatZones.of(heatMap(cells), scale, HeatMetric.CALLS).isEmpty())
    }

    @Test fun a_cor_vai_do_frio_ao_quente_sem_degrau() {
        HeatMetric.entries.forEach { m ->
            val cold = HeatPalette.color(m, 0.0)
            val warm = HeatPalette.color(m, 0.5)
            val hot = HeatPalette.color(m, 1.0)
            assertTrue(cold != warm && warm != hot)
            // A borda da mancha é mais transparente que o miolo: é o que a deixa esfumaçada.
            assertTrue(alpha(cold) < alpha(hot))
            assertTrue(alpha(warm) in alpha(cold)..alpha(hot))
            // Dois valores perto dão cores perto (sem degrau visível).
            assertTrue(abs(alpha(HeatPalette.color(m, 0.50)) - alpha(HeatPalette.color(m, 0.52))) <= 6)
        }
        assertEquals(255, alpha(HeatPalette.solid(HeatPalette.levelColor(HeatMetric.CALLS, 4))))
        val colors = HeatPalette.categoryColors(listOf("UberX", "Comfort", "Modalidade nova"))
        assertEquals(3, colors.size)
        assertEquals(3, colors.values.toSet().size)
        assertEquals(HeatPalette.CATEGORY_COLORS["UberX"], colors["UberX"])
    }

    // --- zonas desenhadas sozinhas ----------------------------------------------------------

    @Test fun hexagonos_vizinhos_viram_uma_zona_e_os_longe_nao() {
        val touching = listOf(cell(0, 0), cell(1, 0), cell(1, -1))
        assertEquals(1, HeatZones.blobs(touching).size)
        val apart = listOf(cell(0, 0), cell(4, 0))
        assertEquals(2, HeatZones.blobs(apart).size)
    }

    @Test fun contorno_de_um_hexagono_tem_6_cantos_e_de_dois_tem_10() {
        val one = listOf(cell(0, 0))
        assertEquals(6, HeatZones.trace(one, size).size)
        val two = listOf(cell(0, 0), cell(1, 0))
        assertEquals(10, HeatZones.trace(two, size).size)
    }

    @Test fun o_contorno_fecha_em_volta_dos_hexagonos() {
        val hexArea = 3.0 * sqrt(3.0) / 2.0 * size * size
        listOf(
            listOf(cell(0, 0)),
            listOf(cell(0, 0), cell(1, 0)),
            listOf(cell(0, 0), cell(1, 0), cell(1, -1)),
            listOf(cell(0, 0), cell(1, 0), cell(-1, 0), cell(0, 1), cell(0, -1), cell(1, -1), cell(-1, 1)),
        ).forEach { cells ->
            val ring = HeatZones.trace(cells, size)
            assertTrue(ring.size >= 6)
            // A área do contorno bate com a soma dos hexágonos: nada de fora, nada faltando.
            assertEquals(cells.size * hexArea, area(ring), hexArea * 0.001)
        }
    }

    @Test fun contorno_sai_em_coordenadas_e_sem_repetir_o_primeiro_ponto() {
        val cells = listOf(cell(0, 0), cell(1, 0))
        val map = heatMap(cells)
        val polygon = HeatZones.outline(cells, map)
        assertEquals(10, polygon.size)
        assertTrue(polygon.first() != polygon.last())
        assertTrue(polygon.all { abs(it.lat - sp.lat) < 0.1 && abs(it.lon - sp.lon) < 0.1 })
    }

    @Test fun grupo_que_encosta_so_pelo_canto_vira_mais_de_uma_zona() {
        // Dois blocos ligados por um caminho que se fecha num canto só.
        val cells = listOf(
            cell(0, 0), cell(2, -1), cell(1, 0), cell(1, -1), cell(2, -2), cell(0, 1), cell(1, 1), cell(2, 0),
        )
        val map = heatMap(cells)
        val parts = HeatZones.split(cells, map)
        assertEquals(cells.size, parts.sumOf { it.first.size })
        parts.forEach { (group, polygon) ->
            assertTrue(group.isNotEmpty())
            assertTrue(polygon.size >= 3)
        }
    }

    @Test fun zona_junta_os_numeros_e_sugere_o_tipo() {
        val cells = listOf(
            cell(0, 0, weight = 10.0, count = 10, perKm = 3.0, category = "UberX"),
            cell(1, 0, weight = 10.0, count = 10, perKm = 2.0, category = "Comfort"),
            cell(6, 0, weight = 0.5, count = 1, perKm = 1.0),
        )
        val map = heatMap(cells)
        val zones = HeatZones.of(map, HeatScale.of(cells, HeatMetric.CALLS)!!, HeatMetric.CALLS)
        assertEquals(1, zones.size)
        val z = zones.first()
        assertEquals(20, z.count)
        assertEquals(2.5, z.avg(HeatMetric.PER_KM)!!, 0.001)
        assertEquals(ZoneType.HIGH_DEMAND, z.suggestedType)
        assertTrue(z.topCategory == "UberX" || z.topCategory == "Comfort")

        val byMoney = HeatZones.of(map, HeatScale.of(cells, HeatMetric.PER_KM)!!, HeatMetric.PER_KM)
        assertEquals(ZoneType.CUSTOM, byMoney.first().suggestedType)
    }

    @Test fun lista_de_zonas_vem_da_mais_quente_para_a_menos_e_respeita_o_maximo() {
        val cells = (0..20).map { cell(it * 4, 0, weight = (it + 1).toDouble(), count = it + 1) }
        val map = heatMap(cells)
        val scale = HeatScale.of(cells, HeatMetric.CALLS)!!
        val zones = HeatZones.of(map, scale, HeatMetric.CALLS, maxZones = 3)
        assertEquals(3, zones.size)
        assertTrue(zones[0].count > zones[1].count)
        assertTrue(zones[1].count > zones[2].count)
    }

    @Test fun sem_hexagono_nao_ha_zona() {
        assertTrue(HeatZones.of(HeatMap.EMPTY, null, HeatMetric.CALLS).isEmpty())
        assertTrue(HeatZones.of(heatMap(listOf(cell(0, 0))), null, HeatMetric.CALLS).isEmpty())
    }

    @Test fun do_historico_ate_a_zona() {
        // 8 ofertas juntas num ponto e 8 em outro, longe: duas manchas, duas zonas.
        val points = List(8) { pointAt(0, 0) } + List(8) { pointAt(12, 0) }
        val map = HeatGrid.build(points, plane, size, HeatGrid.kernelMeters(size))
        val scale = HeatScale.of(map.cells, HeatMetric.CALLS)!!
        val zones = HeatZones.of(map, scale, HeatMetric.CALLS)
        assertEquals(2, zones.size)
        assertEquals(16, zones.sumOf { it.count })
        zones.forEach { assertTrue(it.polygon.size >= 6) }
    }

    // --- ajudantes --------------------------------------------------------------------------

    private fun cell(
        q: Int = 0,
        r: Int = 0,
        weight: Double = 3.0,
        count: Int = 3,
        perKm: Double? = 2.0,
        category: String? = "UberX",
    ): HeatCell = HeatCell(
        q = q,
        r = r,
        count = count,
        accepted = 0,
        weight = weight,
        avgPrice = 20.0,
        avgPerKm = perKm,
        avgPerHour = 40.0,
        avgProfit = 10.0,
        topCategory = category,
        center = plane.geo(HexMath.center(q, r, size)),
        polygon = HexMath.corners(q, r, size).map { plane.geo(it) },
    )

    private fun heatMap(cells: List<HeatCell>) = HeatMap(
        cells = cells,
        plane = plane,
        sizeMeters = size,
        kernelMeters = HeatGrid.kernelMeters(size),
        considered = cells.sumOf { it.count },
        plotted = cells.sumOf { it.count },
    )

    private fun alpha(color: Long): Int = ((color shr 24) and 0xFF).toInt()

    /** Área do polígono (fórmula do cadarço), sempre positiva. */
    private fun area(ring: List<PlanePoint>): Double {
        var sum = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2.0
    }
}
