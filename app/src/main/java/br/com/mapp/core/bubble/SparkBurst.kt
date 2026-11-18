package br.com.mapp.core.bubble

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Força do efeito que sai do ícone "M" quando a corrida começa (ajustes do botão). [DISCRETE] é o
 * padrão: poucas faíscas, perto do botão e por pouco tempo — para avisar sem atrapalhar a tela da
 * Uber nem gastar bateria.
 * - [sparks]: quantas faíscas saem;
 * - [reach]: até onde elas vão, em raios do círculo do botão (1 = a borda do círculo);
 * - [rings]: anéis do estouro (a onda da bolha arrebentando);
 * - [durationMs]: quanto o efeito dura.
 */
enum class SparkLevel(
    val id: String,
    val label: String,
    val sparks: Int,
    val reach: Float,
    val rings: Int,
    val durationMs: Long,
) {
    OFF("off", "Desligado", 0, 0f, 0, 0L),
    DISCRETE("discrete", "Discreto", 12, 1.6f, 1, 700L),
    MEDIUM("medium", "Médio", 26, 2.1f, 1, 900L),
    FIREWORKS("fireworks", "Fogos", 44, 2.8f, 2, 1_200L);

    companion object {
        val DEFAULT = DISCRETE

        fun fromId(id: String?): SparkLevel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Uma faísca do estouro: para onde vai, com que força e por quanto tempo vive. */
data class Spark(
    /** Direção em graus (0 = direita, cresce no sentido horário, como no canvas). */
    val angle: Float,
    /** Fração da distância máxima do nível (0..1). */
    val speed: Float,
    /** Fração da duração do nível que esta faísca dura (0..1). */
    val life: Float,
    /** Qual cor usar: 0 = começo do gradiente do "M", 1 = fim, 2 = branco. */
    val color: Int,
    /** Grossura do risco, em fração da grossura padrão. */
    val width: Float,
)

/** Onde a faísca está num instante (em pixels, a partir do centro do botão). */
data class SparkState(
    val x: Float,
    val y: Float,
    /** Começo do rastro (o risco vai daqui até x/y). */
    val tailX: Float,
    val tailY: Float,
    /** 0..1 */
    val alpha: Float,
    val width: Float,
)

/**
 * Faíscas do ícone "M" quando a corrida começa (bolha estourando + fogos): Kotlin puro, para o
 * overlay só desenhar o que sai daqui. As faíscas são **sempre as mesmas** (sorteio com semente
 * fixa), então o efeito não "pisca" diferente a cada corrida e o teste confere o resultado.
 *
 * As faíscas saem do centro do botão em todas as direções, desaceleram (como fogos de artifício),
 * caem um pouco no fim e somem; os anéis são a onda do estouro, que cresce e desaparece.
 */
object SparkBurst {
    /** Grossura padrão do risco e tamanho do rastro, em raios do círculo do botão. */
    const val WIDTH = 0.085f
    const val TAIL = 0.22f

    /** Quanto as faíscas caem no fim (em raios do círculo), como num fogo de artifício. */
    const val GRAVITY = 0.55f

    /** Começo do anel do estouro (a borda do círculo) e até onde ele cresce. */
    const val RING_START = 1.0f
    const val RING_REACH = 0.85f

    /** Faíscas de um nível, na ordem em que são desenhadas (vazio com o efeito desligado). */
    fun sparks(level: SparkLevel): List<Spark> {
        if (level.sparks <= 0) return emptyList()
        val rnd = Lcg(SEED)
        val step = 360f / level.sparks
        return (0 until level.sparks).map { i ->
            Spark(
                // Uma por setor, com uma variação dentro dele: espalha sem deixar buraco.
                angle = i * step + (rnd.next() * 0.45f * step),
                speed = 0.55f + rnd.next01() * 0.45f,
                life = 0.6f + rnd.next01() * 0.4f,
                color = (rnd.next01() * 3f).toInt().coerceIn(0, 2),
                width = 0.7f + rnd.next01() * 0.6f,
            )
        }
    }

    /**
     * Estado da faísca em [elapsedMs] (null = já morreu). [radius] = raio do círculo do botão em
     * pixels; o resultado já vem em pixels, relativo ao centro.
     */
    fun state(spark: Spark, level: SparkLevel, elapsedMs: Long, radius: Float): SparkState? {
        if (level.sparks <= 0 || level.durationMs <= 0L) return null
        val life = level.durationMs * spark.life
        val t = elapsedMs / life
        if (t < 0f || t >= 1f) return null
        val max = level.reach * spark.speed * radius
        val rad = spark.angle * PI.toFloat() / 180f
        val dx = cos(rad)
        val dy = sin(rad)
        fun at(k: Float): Pair<Float, Float> {
            val f = k.coerceIn(0f, 1f)
            // Sai rápido e vai parando (ease-out), depois cai um pouco.
            val d = max * (1f - (1f - f) * (1f - f))
            return (dx * d) to (dy * d + GRAVITY * radius * f * f)
        }
        val (x, y) = at(t)
        val (tx, ty) = at(t - TAIL)
        return SparkState(
            x = x,
            y = y,
            tailX = tx,
            tailY = ty,
            alpha = (1f - t) * (1f - t),
            width = WIDTH * spark.width * radius,
        )
    }

    /** Raio do anel do estouro em [elapsedMs] (anel [index]); negativo = ainda não começou. */
    fun ringRadius(index: Int, level: SparkLevel, elapsedMs: Long, radius: Float): Float {
        val t = ringFraction(index, level, elapsedMs)
        return radius * (RING_START + RING_REACH * level.reach * (1f - (1f - t) * (1f - t)))
    }

    /** Transparência do anel do estouro (0 = sumiu). */
    fun ringAlpha(index: Int, level: SparkLevel, elapsedMs: Long): Float {
        val t = ringFraction(index, level, elapsedMs)
        return if (t <= 0f || t >= 1f) 0f else (1f - t) * 0.55f
    }

    /** Quanto da vida do anel já passou (0..1); o 2º anel sai um pouco depois do 1º. */
    private fun ringFraction(index: Int, level: SparkLevel, elapsedMs: Long): Float {
        if (index >= level.rings || level.durationMs <= 0L) return 1f
        val delay = level.durationMs * RING_DELAY * index
        val life = level.durationMs * RING_LIFE
        return ((elapsedMs - delay) / life).coerceIn(0f, 1f)
    }

    private const val SEED = 0x5DEECE66DL
    private const val RING_LIFE = 0.55f
    private const val RING_DELAY = 0.18f

    /** Sorteio simples e determinístico: [next] dá −1..1 e [next01] dá 0..1. */
    private class Lcg(private var seed: Long) {
        fun next(): Float = next01() * 2f - 1f

        fun next01(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            // 31 bits do meio da semente, em 0..1 (nunca chega a 1).
            return (seed ushr 33).toFloat() / (1L shl 31).toFloat()
        }
    }
}
