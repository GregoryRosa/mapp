package br.com.mapp.overlay

import android.content.Context
import android.content.SharedPreferences
import br.com.mapp.core.shift.Shift
import br.com.mapp.core.shift.ShiftCodec
import br.com.mapp.core.shift.ShiftTrip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Jornada em andamento (play/pausa no ícone "M") e a última encerrada. Fica salva no aparelho
 * (SharedPreferences) para sobreviver ao fechamento do app ou do copiloto.
 */
object ShiftTracker {
    private const val NAME = "mapp_shift"
    private const val KEY_CURRENT = "current"
    private const val KEY_LAST = "last"

    private val _current = MutableStateFlow<Shift?>(null)
    private val _last = MutableStateFlow<Shift?>(null)
    private var prefs: SharedPreferences? = null

    /** Jornada atual (null = nenhuma). */
    fun current(ctx: Context): StateFlow<Shift?> { load(ctx); return _current.asStateFlow() }

    /** Última jornada encerrada (tela inicial). */
    fun last(ctx: Context): StateFlow<Shift?> { load(ctx); return _last.asStateFlow() }

    fun start(ctx: Context, now: Long = System.currentTimeMillis()) =
        update(ctx) { it?.resume(now) ?: Shift(startedAt = now) }

    fun pause(ctx: Context, now: Long = System.currentTimeMillis()) = update(ctx) { it?.pause(now) }

    /** Encerra e guarda como "última jornada". */
    @Synchronized
    fun end(ctx: Context, now: Long = System.currentTimeMillis()): Shift? {
        load(ctx)
        val ended = _current.value?.pause(now) ?: return null
        _current.value = null
        _last.value = ended
        prefs?.edit()?.remove(KEY_CURRENT)?.putString(KEY_LAST, ShiftCodec.encode(ended))?.apply()
        return ended
    }

    fun addKm(ctx: Context, km: Double) = update(ctx) { it?.addKm(km) }

    fun addTrip(ctx: Context, trip: ShiftTrip) = update(ctx) { it?.addTrip(trip) }

    fun undoLastTrip(ctx: Context) = update(ctx) { it?.undoLastTrip() }

    @Synchronized
    private fun update(ctx: Context, change: (Shift?) -> Shift?) {
        load(ctx)
        val next = change(_current.value)
        if (next == _current.value) return
        _current.value = next
        prefs?.edit()?.apply {
            if (next == null) remove(KEY_CURRENT) else putString(KEY_CURRENT, ShiftCodec.encode(next))
        }?.apply()
    }

    @Synchronized
    private fun load(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        _current.value = ShiftCodec.decode(p.getString(KEY_CURRENT, null))
        _last.value = ShiftCodec.decode(p.getString(KEY_LAST, null))
        prefs = p
    }
}
