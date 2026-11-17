package br.com.mapp.core

import br.com.mapp.core.heat.HeatCell
import br.com.mapp.core.heat.HeatFilter
import br.com.mapp.core.heat.HeatGrid
import br.com.mapp.core.heat.HeatHours
import br.com.mapp.core.heat.HeatMap
import br.com.mapp.core.heat.HeatMetric
import br.com.mapp.core.heat.HeatPalette
import br.com.mapp.core.heat.HeatPeriod
import br.com.mapp.core.heat.HeatPoint
import br.com.mapp.core.heat.HeatPoints
import br.com.mapp.core.heat.HeatScale
import br.com.mapp.core.heat.HeatSet
import br.com.mapp.core.heat.HeatSpot
import br.com.mapp.core.heat.HeatZones
import br.com.mapp.core.model.EvaluatedOffer
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.OfferMetrics
import br.com.mapp.core.model.Platform
import br.com.mapp.core.model.RawOffer
import br.com.mapp.core.model.Signal
import br.com.mapp.core.model.ZoneType
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mapa de calor das zonas: grade, escala de cor e zonas desenhadas sozinhas. */
class HeatMapTest {

    private val sp = GeoPoint(-23.5505, -46.6333)
    private val zone = ZoneId.of("America/Sao_Paulo")

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

        val uber = HeatPoints.filter(points, HeatFilter(platforms = setOf(Platform.UBER)), now, zone)
        assertEquals(3, uber.size)

        val uberX = HeatPoints.filter(points, HeatFilter(categories = setOf("UberX")), now, zone)
        assertEquals(2, uberX.size)

        val accepted = HeatPoints.filter(points, HeatFilter(set = HeatSet.ACCEPTED), now, zone)
        assertEquals(1, accepted.size)
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

    // --- grade ------------------------------------------------------------------------------

    @Test fun quadradinho_diminui_quando_o_zoom_aumenta() {
        val far = HeatGrid.cellMeters(10.0)
        val city = HeatGrid.cellMeters(14.5)
        val close = HeatGrid.cellMeters(18.0)
        assertTrue(far > city)
        assertTrue(city > close)
    }

    @Test fun quadradinho_com_pouca_oferta_nao_pinta() {
        val meters = 300.0
        val points = List(3) { point() } + listOf(point(lat = sp.lat + 0.05))
        val map = HeatGrid.build(points, meters)
        assertEquals(1, map.cells.size)
        assertEquals(3, map.cells.first().count)
        assertEquals(4, map.considered)
        assertEquals(3, map.plotted)
    }

    @Test fun pontos_perto_caem_no_mesmo_quadradinho_e_longe_em_outro() {
        val meters = 500.0
        val d = HeatGrid.latStep(meters)
        // Dentro do quadradinho y = 10 e, bem longe, dentro do y = 30.
        val near = List(3) { i -> point(lat = (10.5 + (i - 1) * 0.1) * d, lon = 0.5 * d) }
        val far = List(3) { i -> point(lat = (30.5 + (i - 1) * 0.1) * d, lon = 0.5 * d) }
        val map = HeatGrid.build(near + far, meters)
        assertEquals(2, map.cells.size)
        assertTrue(map.cells.all { it.count == 3 })
        assertEquals(2, map.cells.map { it.y }.toSet().size)
        assertEquals(1, map.cells.map { it.x }.toSet().size)
    }

    @Test fun quadradinho_resume_media_e_modalidade_mais_vista() {
        val points = listOf(
            point(price = 10.0, perKm = 1.0, perHour = 30.0, profit = 5.0, category = "UberX"),
            point(price = 20.0, perKm = 2.0, perHour = 40.0, profit = 10.0, category = "UberX"),
            point(price = 30.0, perKm = 3.0, perHour = null, profit = null, category = "Comfort"),
        )
        val cell = HeatGrid.build(points, 500.0).cells.single()
        assertEquals(20.0, cell.avgPrice, 0.001)
        assertEquals(2.0, cell.avgPerKm!!, 0.001)
        assertEquals(35.0, cell.avgPerHour!!, 0.001)
        assertEquals(7.5, cell.avgProfit!!, 0.001)
        assertEquals("UberX", cell.topCategory)
        assertEquals(3, cell.count)
        assertEquals(4, cell.polygon.size)
    }

    @Test fun quadradinho_sem_o_numero_da_metrica_nao_tem_valor() {
        val points = List(3) { point(perKm = null, perHour = null, profit = null) }
        val cell = HeatGrid.build(points, 500.0).cells.single()
        assertNull(cell.value(HeatMetric.PER_KM))
        assertNull(cell.value(HeatMetric.PER_HOUR))
        assertNull(cell.value(HeatMetric.PROFIT))
        assertEquals(3.0, cell.value(HeatMetric.CALLS)!!, 0.001)
        assertNull(HeatScale.of(listOf(cell), HeatMetric.PER_KM))
    }

    // --- escala de cor ----------------------------------------------------------------------

    @Test fun chamadas_mais_altas_ficam_no_nivel_mais_quente() {
        val cells = listOf(cell(count = 10), cell(x = 1, count = 5), cell(x = 2, count = 1))
        val scale = HeatScale.of(cells, HeatMetric.CALLS)!!
        assertEquals(HeatPalette.LEVELS - 1, scale.level(cells[0], HeatMetric.CALLS))
        assertTrue(scale.level(cells[1], HeatMetric.CALLS)!! in 1..3)
        assertEquals(0, scale.level(cells[2], HeatMetric.CALLS))
        assertTrue(scale.isHot(cells[0], HeatMetric.CALLS))
        assertFalse(scale.isHot(cells[2], HeatMetric.CALLS))
    }

    @Test fun dinheiro_usa_a_posicao_na_fila_e_o_melhor_e_sempre_o_mais_quente() {
        val cells = (0..9).map { cell(x = it, perKm = 2.0 + it * 0.05) }
        val scale = HeatScale.of(cells, HeatMetric.PER_KM)!!
        assertEquals(HeatPalette.LEVELS - 1, scale.level(cells.last(), HeatMetric.PER_KM))
        assertEquals(0, scale.level(cells.first(), HeatMetric.PER_KM))
        assertTrue(scale.isHot(cells.last(), HeatMetric.PER_KM))
        assertFalse(scale.isHot(cells.first(), HeatMetric.PER_KM))
    }

    @Test fun quando_nada_se_destaca_fica_tudo_no_meio_e_sem_zona() {
        val cells = (0..4).map { cell(x = it, count = 4) }
        val scale = HeatScale.of(cells, HeatMetric.CALLS)!!
        cells.forEach { assertEquals(HeatPalette.LEVELS / 2, scale.level(it, HeatMetric.CALLS)) }
        assertTrue(HeatZones.of(heatMap(cells), scale, HeatMetric.CALLS).isEmpty())
    }

    @Test fun cor_do_nivel_existe_para_toda_metrica() {
        HeatMetric.entries.forEach { m ->
            (0 until HeatPalette.LEVELS).forEach { l ->
                assertTrue(HeatPalette.color(m, l) != 0L)
            }
        }
        assertEquals(HeatPalette.color(HeatMetric.CALLS, 9), HeatPalette.color(HeatMetric.CALLS, 4))
        val colors = HeatPalette.categoryColors(listOf("UberX", "Comfort", "Modalidade nova"))
        assertEquals(3, colors.size)
        assertEquals(3, colors.values.toSet().size)
        assertEquals(HeatPalette.CATEGORY_COLORS["UberX"], colors["UberX"])
    }

    // --- zonas desenhadas sozinhas ----------------------------------------------------------

    @Test fun quadradinhos_vizinhos_viram_uma_zona_e_o_canto_nao_junta() {
        val touching = listOf(cell(0, 0), cell(1, 0), cell(1, 1))
        assertEquals(1, HeatZones.blobs(touching).size)
        val diagonal = listOf(cell(0, 0), cell(1, 1))
        assertEquals(2, HeatZones.blobs(diagonal).size)
        val apart = listOf(cell(0, 0), cell(9, 9))
        assertEquals(2, HeatZones.blobs(apart).size)
    }

    @Test fun contorno_do_quadrado_tem_4_cantos_e_do_L_tem_6() {
        val map = heatMap(listOf(cell(0, 0), cell(1, 0), cell(0, 1), cell(1, 1)))
        assertEquals(4, HeatZones.outline(map.cells, map).size)
        val l = heatMap(listOf(cell(0, 0), cell(1, 0), cell(1, 1)))
        assertEquals(6, HeatZones.outline(l.cells, l).size)
        val one = heatMap(listOf(cell(0, 0)))
        assertEquals(4, HeatZones.outline(one.cells, one).size)
    }

    @Test fun contorno_fecha_em_volta_dos_quadradinhos() {
        val cells = listOf(cell(0, 0), cell(1, 0), cell(1, 1))
        val map = heatMap(cells)
        val polygon = HeatZones.outline(cells, map)
        // Sem repetir o 1º ponto no fim (contrato de Zone.polygon).
        assertTrue(polygon.first() != polygon.last())
        val lats = polygon.map { it.lat }
        val lons = polygon.map { it.lon }
        assertEquals(0.0, lons.min(), 1e-9)
        assertEquals(2 * map.lonStep, lons.max(), 1e-9)
        assertEquals(0.0, lats.min(), 1e-9)
        assertEquals(2 * map.latStep, lats.max(), 1e-9)
    }

    @Test fun grupo_que_encosta_so_pelo_canto_vira_mais_de_uma_zona() {
        // Dois blocos ligados por um caminho que se toca num canto só.
        val cells = listOf(
            cell(0, 0), cell(1, 1), cell(2, 0), cell(1, -1),
            cell(0, -2), cell(1, -2), cell(2, -2), cell(3, -1), cell(3, 0), cell(2, 1),
        )
        val map = heatMap(cells)
        val parts = HeatZones.split(cells, map)
        assertTrue(parts.size >= 2)
        assertEquals(cells.size, parts.sumOf { it.first.size })
        parts.forEach { (group, polygon) ->
            assertTrue(group.isNotEmpty())
            assertTrue(polygon.size >= 3)
        }
    }

    @Test fun zona_junta_os_numeros_e_sugere_o_tipo() {
        val cells = listOf(
            cell(0, 0, count = 10, perKm = 3.0, category = "UberX"),
            cell(1, 0, count = 10, perKm = 2.0, category = "Comfort"),
            cell(5, 5, count = 1, perKm = 1.0),
        )
        val map = heatMap(cells)
        val scale = HeatScale.of(cells, HeatMetric.CALLS)!!
        val zones = HeatZones.of(map, scale, HeatMetric.CALLS)
        assertEquals(1, zones.size)
        val z = zones.first()
        assertEquals(20, z.count)
        assertEquals(2.5, z.avg(HeatMetric.PER_KM)!!, 0.001)
        assertEquals(ZoneType.HIGH_DEMAND, z.suggestedType)
        assertTrue(z.topCategory == "UberX" || z.topCategory == "Comfort")
        assertEquals(ZoneType.CUSTOM, HeatZones.of(map, HeatScale.of(cells, HeatMetric.PER_KM)!!, HeatMetric.PER_KM).first().suggestedType)
    }

    @Test fun lista_de_zonas_vem_da_mais_quente_para_a_menos_e_respeita_o_maximo() {
        val cells = (0..20).map { cell(x = it * 3, count = it + 1) }
        val map = heatMap(cells)
        val scale = HeatScale.of(cells, HeatMetric.CALLS)!!
        val zones = HeatZones.of(map, scale, HeatMetric.CALLS, maxZones = 3)
        assertEquals(3, zones.size)
        assertTrue(zones[0].count > zones[1].count)
        assertTrue(zones[1].count > zones[2].count)
    }

    @Test fun sem_quadradinho_nao_ha_zona() {
        assertTrue(HeatZones.of(HeatMap.EMPTY, null, HeatMetric.CALLS).isEmpty())
        val cells = listOf(cell(0, 0))
        assertTrue(HeatZones.of(heatMap(cells), null, HeatMetric.CALLS).isEmpty())
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

    // --- ajudantes --------------------------------------------------------------------------

    private val step = HeatGrid.latStep(300.0)

    private fun cell(
        x: Int = 0,
        y: Int = 0,
        count: Int = 3,
        perKm: Double? = 2.0,
        category: String? = "UberX",
    ): HeatCell = HeatCell(
        x = x,
        y = y,
        count = count,
        accepted = 0,
        avgPrice = 20.0,
        avgPerKm = perKm,
        avgPerHour = 40.0,
        avgProfit = 10.0,
        topCategory = category,
        center = GeoPoint((y + 0.5) * step, (x + 0.5) * step),
        southWest = GeoPoint(y * step, x * step),
        northEast = GeoPoint((y + 1) * step, (x + 1) * step),
    )

    private fun heatMap(cells: List<HeatCell>) = HeatMap(
        cells = cells,
        latStep = step,
        lonStep = step,
        meters = 300.0,
        considered = cells.sumOf { it.count },
        plotted = cells.sumOf { it.count },
    )
}
