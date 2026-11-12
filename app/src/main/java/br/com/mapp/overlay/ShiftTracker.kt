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
    private const val KEY_HISTORY = "history"
    /** Jornadas guardadas no histórico (~1 ano de trabalho diário). */
    private const val HISTORY_MAX = 400

    private val _current = MutableStateFlow<Shift?>(null)
    private val _last = MutableStateFlow<Shift?>(null)
    private val _history = MutableStateFlow<List<Shift>>(emptyList())
    private var prefs: SharedPreferences? = null

    /** Jornada atual (null = nenhuma). */
    fun current(ctx: Context): StateFlow<Shift?> { load(ctx); return _current.asStateFlow() }

    /** Última jornada encerrada (tela inicial). */
    fun last(ctx: Context): StateFlow<Shift?> { load(ctx); return _last.asStateFlow() }

    /** Jornadas encerradas, da mais nova para a mais antiga. */
    fun history(ctx: Context): StateFlow<List<Shift>> { load(ctx); return _history.asStateFlow() }

    /** Apaga uma jornada do histórico (identificada pelo início). */
    @Synchronized
    fun deleteFromHistory(ctx: Context, startedAt: Long) {
        load(ctx)
        val list = _history.value.filterNot { it.startedAt == startedAt }
        _history.value = list
        val edit = prefs?.edit()?.putString(KEY_HISTORY, ShiftCodec.encodeList(list))
        if (_last.value?.startedAt == startedAt) {
            _last.value = null
            edit?.remove(KEY_LAST)
        }
        edit?.apply()
    }

    fun start(ctx: Context, now: Long = System.currentTimeMillis()) =
        update(ctx) { it?.resume(now) ?: Shift(startedAt = now) }

    fun pause(ctx: Context, now: Long = System.currentTimeMillis()) = update(ctx) { it?.pause(now) }

    /** Encerra, guarda como "última jornada" e no histórico (com o custo/km de agora). */
    @Synchronized
    fun end(ctx: Context, costPerKm: Double? = null, now: Long = System.currentTimeMillis()): Shift? {
        load(ctx)
        val ended = _current.value?.pause(now)?.copy(costPerKm = costPerKm) ?: return null
        val list = (listOf(ended) + _history.value).take(HISTORY_MAX)
        _current.value = null
        _last.value = ended
        _history.value = list
        prefs?.edit()
            ?.remove(KEY_CURRENT)
            ?.putString(KEY_LAST, ShiftCodec.encode(ended))
            ?.putString(KEY_HISTORY, ShiftCodec.encodeList(list))
            ?.apply()
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
        // Versões antigas só guardavam a última jornada: ela vira o 1º item do histórico.
        _history.value = ShiftCodec.decodeList(p.getString(KEY_HISTORY, null))
            .ifEmpty { listOfNotNull(_last.value) }
        prefs = p
    }
}
