package br.com.mapp.core.heat

import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.ZoneType
import kotlin.math.abs
import kotlin.math.round

/**
 * Zona que o mapa desenhou sozinho: hexágonos quentes que se tocam, juntos num polígono.
 * [polygon] já está pronto para salvar como [br.com.mapp.core.model.Zone] (sem repetir o 1º ponto).
 */
data class HeatZone(
    val cells: List<HeatCell>,
    val polygon: List<GeoPoint>,
    val center: GeoPoint,
    val metric: HeatMetric,
) {
    val count: Int get() = cells.sumOf { it.count }
    val accepted: Int get() = cells.sumOf { it.accepted }

    /** Média de uma métrica na zona, pesada pela densidade de cada hexágono. */
    fun avg(of: HeatMetric): Double? {
        var sum = 0.0
        var weight = 0.0
        cells.forEach { c ->
            val v = c.value(of) ?: return@forEach
            if (of == HeatMetric.CALLS || of == HeatMetric.CATEGORY) {
                sum += v
                weight += 1.0
            } else {
                sum += v * c.weight
                weight += c.weight
            }
        }
        return if (weight <= 0.0) null else sum / weight
    }

    /** Valor da métrica que criou a zona (é por ele que a lista é ordenada). */
    val value: Double? get() = if (metric == HeatMetric.CALLS) count.toDouble() else avg(metric)

    /** Modalidade mais vista na zona. */
    val topCategory: String?
        get() = cells.mapNotNull { c -> c.topCategory?.let { it to c.count } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key

    /** Tipo sugerido ao salvar: muita chamada = alta demanda; o resto, personalizada. */
    val suggestedType: ZoneType
        get() = if (metric == HeatMetric.CALLS || metric == HeatMetric.CATEGORY) {
            ZoneType.HIGH_DEMAND
        } else {
            ZoneType.CUSTOM
        }
}

/**
 * Junta os hexágonos quentes em zonas e traça o contorno da união.
 *
 * O contorno é o caminho que sobra quando os lados compartilhados entre dois hexágonos se cancelam:
 * as arestas de cada hexágono são orientadas no sentido anti-horário, então o lado comum aparece
 * duas vezes, em sentidos opostos.
 */
object HeatZones {

    /** Máximo de zonas na lista (as mais quentes primeiro). */
    const val MAX_ZONES = 12

    /** Canto da grade, arredondado para os hexágonos vizinhos acharem o mesmo ponto. */
    data class Corner(val ix: Long, val iy: Long)

    fun of(
        map: HeatMap,
        scale: HeatScale?,
        metric: HeatMetric,
        maxZones: Int = MAX_ZONES,
    ): List<HeatZone> {
        if (map.isEmpty || scale == null) return emptyList()
        val hot = map.cells.filter { scale.isHot(it, metric) }
        if (hot.isEmpty()) return emptyList()
        return blobs(hot)
            .flatMap { group -> split(group, map) }
            .map { (cells, polygon) ->
                HeatZone(
                    cells = cells,
                    polygon = polygon,
                    center = center(cells),
                    metric = metric,
                )
            }
            .sortedWith(compareByDescending<HeatZone> { it.value ?: Double.NEGATIVE_INFINITY })
            .take(maxZones)
    }

    /** Grupos de hexágonos que dividem um lado. */
    fun blobs(cells: List<HeatCell>): List<List<HeatCell>> {
        val byKey = HashMap<Long, HeatCell>(cells.size * 2)
        cells.forEach { byKey[it.key] = it }
        val seen = HashSet<Long>()
        val out = ArrayList<List<HeatCell>>()
        cells.forEach { start ->
            if (start.key in seen) return@forEach
            val group = ArrayList<HeatCell>()
            val queue = ArrayDeque<Long>()
            queue.addLast(start.key)
            seen.add(start.key)
            while (queue.isNotEmpty()) {
                val k = queue.removeFirst()
                val cell = byKey[k] ?: continue
                group.add(cell)
                HexMath.NEIGHBORS.forEach { (dq, dr) ->
                    val n = HexMath.key(cell.q + dq, cell.r + dr)
                    if (n !in seen && byKey.containsKey(n)) {
                        seen.add(n)
                        queue.addLast(n)
                    }
                }
            }
            out.add(group)
        }
        return out
    }

    /**
     * Contorno da união dos hexágonos, no sentido anti-horário, sem repetir o 1º ponto.
     * Se o caminho não fechar, cai na caixa que cobre o grupo (nunca devolve lixo).
     */
    fun outline(cells: List<HeatCell>, map: HeatMap): List<GeoPoint> {
        val ring = trace(cells, map.sizeMeters)
        val points = ring.map { map.plane.geo(it.x, it.y) }
        return if (points.size >= 3) points else boundingBox(cells, map)
    }

    /**
     * Um grupo pode encostar em si mesmo só pelo canto (dois blocos ligados por um caminho longo).
     * O contorno de um caminho só não dá conta disso, então o grupo vira mais de uma zona, cada uma
     * com o seu contorno fechado e os hexágonos que estão dentro dele.
     */
    fun split(cells: List<HeatCell>, map: HeatMap): List<Pair<List<HeatCell>, List<GeoPoint>>> {
        val out = ArrayList<Pair<List<HeatCell>, List<GeoPoint>>>()
        var remaining = cells
        var guard = cells.size + 2
        while (remaining.isNotEmpty() && guard-- > 0) {
            val ring = trace(remaining, map.sizeMeters)
            if (ring.size < 3) {
                out.add(remaining to boundingBox(remaining, map))
                break
            }
            val inside = remaining.filter { isInside(ring, HexMath.center(it.q, it.r, map.sizeMeters)) }
            if (inside.isEmpty()) {
                out.add(remaining to boundingBox(remaining, map))
                break
            }
            out.add(inside to ring.map { map.plane.geo(it.x, it.y) })
            val used = inside.map { it.key }.toHashSet()
            remaining = remaining.filter { it.key !in used }
        }
        return out
    }

    /** Caixa que cobre todos os hexágonos do grupo (reserva). */
    fun boundingBox(cells: List<HeatCell>, map: HeatMap): List<GeoPoint> {
        if (cells.isEmpty()) return emptyList()
        val corners = cells.flatMap { HexMath.corners(it.q, it.r, map.sizeMeters) }
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }
        val maxY = corners.maxOf { it.y }
        return listOf(
            map.plane.geo(minX, minY),
            map.plane.geo(maxX, minY),
            map.plane.geo(maxX, maxY),
            map.plane.geo(minX, maxY),
        )
    }

    /**
     * Caminha pelas arestas da borda e devolve os cantos do contorno, no plano em metros.
     * Lista vazia se o caminho não fechar (aí o chamador usa a caixa).
     */
    fun trace(cells: List<HeatCell>, size: Double): List<PlanePoint> {
        if (cells.isEmpty() || size <= 0.0) return emptyList()
        val eps = size / 1_000.0
        val places = HashMap<Corner, PlanePoint>(cells.size * 8)
        val edges = HashSet<Pair<Corner, Corner>>(cells.size * 8)

        cells.forEach { cell ->
            val corners = HexMath.corners(cell.q, cell.r, size)
            val keys = corners.map { p ->
                val k = cornerOf(p, eps)
                places[k] = p
                k
            }
            for (i in keys.indices) {
                val edge = keys[i] to keys[(i + 1) % keys.size]
                val reverse = edge.second to edge.first
                if (!edges.remove(reverse)) edges.add(edge)
            }
        }
        if (edges.isEmpty()) return emptyList()

        val next = HashMap<Corner, MutableList<Corner>>()
        edges.forEach { (from, to) -> next.getOrPut(from) { mutableListOf() }.add(to) }
        val start = next.keys.minWithOrNull(compareBy<Corner> { it.iy }.thenBy { it.ix }) ?: return emptyList()

        val path = ArrayList<PlanePoint>()
        var current = start
        var incoming = PlanePoint(1.0, 0.0)
        var guard = edges.size + 2
        while (guard-- > 0) {
            val options = next[current]
            if (options.isNullOrEmpty()) return emptyList()
            val here = places[current] ?: return emptyList()
            val step = pick(here, options, places, incoming)
            options.remove(step)
            path.add(here)
            val there = places[step] ?: return emptyList()
            incoming = PlanePoint(there.x - here.x, there.y - here.y)
            current = step
            if (current == start) return simplify(path)
        }
        return emptyList()
    }

    private fun cornerOf(p: PlanePoint, eps: Double): Corner =
        Corner(round(p.x / eps).toLong(), round(p.y / eps).toLong())

    /**
     * Com mais de um caminho possível (dois hexágonos que se tocam só pelo canto), segue o que vira
     * mais para a esquerda — é o que mantém o miolo da zona do lado de dentro.
     */
    private fun pick(
        here: PlanePoint,
        options: List<Corner>,
        places: Map<Corner, PlanePoint>,
        incoming: PlanePoint,
    ): Corner {
        if (options.size == 1) return options.first()
        return options.maxWithOrNull(
            compareBy<Corner> { option ->
                val p = places[option]
                if (p == null) -3 else turn(incoming, PlanePoint(p.x - here.x, p.y - here.y))
            },
        ) ?: options.first()
    }

    /** 1 = vira à esquerda, 0 = segue reto, -1 = vira à direita, -2 = volta por onde veio. */
    private fun turn(incoming: PlanePoint, direction: PlanePoint): Int {
        val cross = incoming.x * direction.y - incoming.y * direction.x
        val dot = incoming.x * direction.x + incoming.y * direction.y
        return when {
            cross > 1e-9 -> 1
            cross < -1e-9 -> -1
            dot > 0 -> 0
            else -> -2
        }
    }

    /** Tira os cantos no meio de um lado reto. */
    private fun simplify(path: List<PlanePoint>): List<PlanePoint> {
        if (path.size < 3) return path
        val out = ArrayList<PlanePoint>(path.size)
        for (i in path.indices) {
            val prev = path[(i - 1 + path.size) % path.size]
            val cur = path[i]
            val nxt = path[(i + 1) % path.size]
            val ax = cur.x - prev.x
            val ay = cur.y - prev.y
            val bx = nxt.x - cur.x
            val by = nxt.y - cur.y
            val cross = ax * by - ay * bx
            val scale = maxOf(abs(ax) + abs(ay), abs(bx) + abs(by), 1e-6)
            if (abs(cross) > 1e-6 * scale * scale) out.add(cur)
        }
        return if (out.size >= 3) out else path
    }

    /** O centro do hexágono cai dentro do contorno? (raio para a esquerda) */
    private fun isInside(ring: List<PlanePoint>, point: PlanePoint): Boolean {
        var hit = false
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            if ((a.y > point.y) != (b.y > point.y)) {
                val t = (point.y - a.y) / (b.y - a.y)
                val x = a.x + t * (b.x - a.x)
                if (point.x < x) hit = !hit
            }
        }
        return hit
    }

    private fun center(cells: List<HeatCell>): GeoPoint {
        var lat = 0.0
        var lon = 0.0
        var weight = 0.0
        cells.forEach { c ->
            lat += c.center.lat * c.weight
            lon += c.center.lon * c.weight
            weight += c.weight
        }
        if (weight <= 0.0) return cells.first().center
        return GeoPoint(lat / weight, lon / weight)
    }
}
