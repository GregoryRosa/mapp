package br.com.mapp.core.heat

import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.ZoneType

/**
 * Zona que o mapa desenhou sozinho: quadradinhos quentes que se tocam, juntos num polígono.
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

    /** Média de uma métrica na zona, pesada pelo nº de ofertas de cada quadradinho. */
    fun avg(of: HeatMetric): Double? {
        var sum = 0.0
        var weight = 0
        cells.forEach { c ->
            val v = c.value(of) ?: return@forEach
            if (of == HeatMetric.CALLS || of == HeatMetric.CATEGORY) {
                sum += v
                weight += 1
            } else {
                sum += v * c.count
                weight += c.count
            }
        }
        return if (weight == 0) null else sum / weight
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
 * Junta os quadradinhos quentes em zonas e traça o contorno da união.
 *
 * O contorno é o caminho que sobra quando os lados compartilhados entre dois quadradinhos se
 * cancelam (as arestas de cada quadradinho são orientadas no sentido anti-horário, então o lado
 * comum aparece duas vezes em sentidos opostos).
 */
object HeatZones {

    /** Máximo de zonas na lista (as mais quentes primeiro). */
    const val MAX_ZONES = 12

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

    /** Grupos de quadradinhos que se tocam pelos lados (canto a canto não junta). */
    fun blobs(cells: List<HeatCell>): List<List<HeatCell>> {
        val byKey = HashMap<Long, HeatCell>(cells.size * 2)
        cells.forEach { byKey[key(it.x, it.y)] = it }
        val seen = HashSet<Long>()
        val out = ArrayList<List<HeatCell>>()
        cells.forEach { start ->
            val startKey = key(start.x, start.y)
            if (startKey in seen) return@forEach
            val group = ArrayList<HeatCell>()
            val queue = ArrayDeque<Long>()
            queue.addLast(startKey)
            seen.add(startKey)
            while (queue.isNotEmpty()) {
                val k = queue.removeFirst()
                val cell = byKey[k] ?: continue
                group.add(cell)
                neighbours(cell.x, cell.y).forEach { n ->
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
     * Contorno da união dos quadradinhos, no sentido anti-horário, sem repetir o 1º ponto.
     * Se algo der errado no caminho, cai no retângulo que cobre o grupo (nunca devolve lixo).
     */
    fun outline(cells: List<HeatCell>, map: HeatMap): List<GeoPoint> {
        val corners = trace(cells)
        val points = corners.map { map.corner(it.x, it.y) }
        return if (points.size >= 3) points else boundingBox(cells, map)
    }

    /**
     * Um grupo pode encostar em si mesmo só pelo canto (dois blocos ligados por um caminho longo).
     * O contorno de um caminho só não dá conta disso, então o grupo vira mais de uma zona, cada uma
     * com o seu contorno fechado e os quadradinhos que estão dentro dele.
     */
    fun split(cells: List<HeatCell>, map: HeatMap): List<Pair<List<HeatCell>, List<GeoPoint>>> {
        val out = ArrayList<Pair<List<HeatCell>, List<GeoPoint>>>()
        var remaining = cells
        var guard = cells.size + 2
        while (remaining.isNotEmpty() && guard-- > 0) {
            val ring = trace(remaining)
            if (ring.size < 3) {
                out.add(remaining to boundingBox(remaining, map))
                break
            }
            val inside = remaining.filter { isInside(ring, it) }
            if (inside.isEmpty()) {
                out.add(remaining to boundingBox(remaining, map))
                break
            }
            out.add(inside to ring.map { map.corner(it.x, it.y) })
            val used = inside.map { key(it.x, it.y) }.toHashSet()
            remaining = remaining.filter { key(it.x, it.y) !in used }
        }
        return out
    }

    /** O centro do quadradinho cai dentro do contorno? (raio para a esquerda) */
    private fun isInside(ring: List<Corner>, cell: HeatCell): Boolean {
        val px = cell.x + 0.5
        val py = cell.y + 0.5
        var hit = false
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            if ((a.y > py) != (b.y > py)) {
                val t = (py - a.y) / (b.y - a.y).toDouble()
                val x = a.x + t * (b.x - a.x)
                if (px < x) hit = !hit
            }
        }
        return hit
    }

    /** Retângulo que cobre todos os quadradinhos do grupo. */
    fun boundingBox(cells: List<HeatCell>, map: HeatMap): List<GeoPoint> {
        if (cells.isEmpty()) return emptyList()
        val minX = cells.minOf { it.x }
        val maxX = cells.maxOf { it.x } + 1
        val minY = cells.minOf { it.y }
        val maxY = cells.maxOf { it.y } + 1
        return listOf(
            map.corner(minX, minY),
            map.corner(maxX, minY),
            map.corner(maxX, maxY),
            map.corner(minX, maxY),
        )
    }

    private fun center(cells: List<HeatCell>): GeoPoint {
        var lat = 0.0
        var lon = 0.0
        var weight = 0
        cells.forEach { c ->
            lat += c.center.lat * c.count
            lon += c.center.lon * c.count
            weight += c.count
        }
        if (weight == 0) return cells.first().center
        return GeoPoint(lat / weight, lon / weight)
    }

    /** Canto da grade (x, y = índices dos cantos, não dos quadradinhos). */
    data class Corner(val x: Int, val y: Int)

    /**
     * Caminha pelas arestas da borda e devolve os cantos do contorno, já sem os pontos no meio de
     * um lado reto. Lista vazia se o caminho não fechar (aí o chamador usa o retângulo).
     */
    fun trace(cells: List<HeatCell>): List<Corner> {
        if (cells.isEmpty()) return emptyList()
        // Arestas de cada quadradinho no sentido anti-horário; o lado comum a dois quadradinhos
        // aparece nos dois sentidos e é descartado.
        val edges = HashSet<Pair<Corner, Corner>>()
        cells.forEach { c ->
            val sw = Corner(c.x, c.y)
            val se = Corner(c.x + 1, c.y)
            val ne = Corner(c.x + 1, c.y + 1)
            val nw = Corner(c.x, c.y + 1)
            listOf(sw to se, se to ne, ne to nw, nw to sw).forEach { e ->
                val reverse = e.second to e.first
                if (!edges.remove(reverse)) edges.add(e)
            }
        }
        if (edges.isEmpty()) return emptyList()
        val next = HashMap<Corner, MutableList<Corner>>()
        edges.forEach { (from, to) -> next.getOrPut(from) { mutableListOf() }.add(to) }

        // Começa no canto mais ao sul e, nele, mais a oeste: ali a aresta da borda vai para o leste.
        val start = next.keys.minWithOrNull(compareBy<Corner> { it.y }.thenBy { it.x }) ?: return emptyList()
        val path = ArrayList<Corner>()
        var current = start
        var incoming = Corner(1, 0) // veio do oeste: entrou andando para o leste
        var guard = edges.size + 2
        while (guard-- > 0) {
            val options = next[current]
            if (options.isNullOrEmpty()) return emptyList()
            val step = pick(current, options, incoming)
            options.remove(step)
            path.add(current)
            incoming = Corner(step.x - current.x, step.y - current.y)
            current = step
            if (current == start) return simplify(path)
        }
        return emptyList()
    }

    /**
     * Com mais de um caminho possível (dois quadradinhos que se tocam só pelo canto), segue o que
     * vira mais para a esquerda — é o que mantém o miolo da zona do lado de dentro.
     */
    private fun pick(current: Corner, options: List<Corner>, incoming: Corner): Corner {
        if (options.size == 1) return options.first()
        return options.maxWithOrNull(
            compareBy<Corner> { turn(incoming, Corner(it.x - current.x, it.y - current.y)) },
        ) ?: options.first()
    }

    /** 1 = vira à esquerda, 0 = segue reto, -1 = vira à direita, -2 = volta por onde veio. */
    private fun turn(incoming: Corner, direction: Corner): Int {
        val cross = incoming.x * direction.y - incoming.y * direction.x
        val dot = incoming.x * direction.x + incoming.y * direction.y
        return when {
            cross > 0 -> 1
            cross < 0 -> -1
            dot > 0 -> 0
            else -> -2
        }
    }

    /** Tira os cantos no meio de um lado reto. */
    private fun simplify(path: List<Corner>): List<Corner> {
        if (path.size < 3) return path
        val out = ArrayList<Corner>(path.size)
        for (i in path.indices) {
            val prev = path[(i - 1 + path.size) % path.size]
            val cur = path[i]
            val nxt = path[(i + 1) % path.size]
            val a = Corner(cur.x - prev.x, cur.y - prev.y)
            val b = Corner(nxt.x - cur.x, nxt.y - cur.y)
            val collinear = a.x * b.y - a.y * b.x == 0
            if (!collinear) out.add(cur)
        }
        return if (out.size >= 3) out else path
    }

    private fun neighbours(x: Int, y: Int): List<Long> =
        listOf(key(x + 1, y), key(x - 1, y), key(x, y + 1), key(x, y - 1))

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
}
