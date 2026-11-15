package br.com.mapp.overlay

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Registro das corridas para diagnóstico: ofertas vistas, telas lidas depois delas (aceite, embarque,
 * fim), o que o copiloto decidiu e como ficou a jornada. Guarda as últimas [MAX] linhas **só no
 * aparelho** (sobrevive ao copiloto ser reiniciado); sai de lá apenas quando o motorista toca em
 * "Registro" no Histórico de jornadas, que copia o texto para colar onde quiser.
 */
object TripLog {
    private const val NAME = "mapp_trip_log"
    private const val KEY = "lines"
    private const val MAX = 250
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val lines = ArrayDeque<String>()
    private var prefs: SharedPreferences? = null
    private var lastBody: String? = null

    /** Acrescenta uma linha com a hora. Linha igual à anterior (tela repetida) não entra de novo. */
    @Synchronized
    fun add(ctx: Context, body: String) {
        load(ctx)
        val clean = body.replace('\n', ' ')
        if (clean == lastBody) return
        lastBody = clean
        lines.addLast("${LocalTime.now().format(CLOCK)} $clean")
        while (lines.size > MAX) lines.removeFirst()
        prefs?.edit()?.putString(KEY, lines.joinToString("\n"))?.apply()
    }

    /** Registro inteiro, da linha mais antiga para a mais nova. */
    @Synchronized
    fun text(ctx: Context): String {
        load(ctx)
        return lines.joinToString("\n")
    }

    private fun load(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        prefs = p
        p.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }?.let { lines.addAll(it) }
    }
}
