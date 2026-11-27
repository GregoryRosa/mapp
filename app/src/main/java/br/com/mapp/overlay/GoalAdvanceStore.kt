package br.com.mapp.overlay

import android.content.Context
import android.content.SharedPreferences
import br.com.mapp.core.goal.AdvanceBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Corridas adiantadas para as despesas (meta do dia batida) e o estado da meta do dia
 * (`core/goal/GoalAdvance`). Só no aparelho (SharedPreferences), como a jornada.
 */
object GoalAdvanceStore {
    private const val NAME = "mapp_goal_advance"
    private const val KEY = "book"

    private val _book = MutableStateFlow(AdvanceBook())
    private var prefs: SharedPreferences? = null

    fun book(ctx: Context): StateFlow<AdvanceBook> { load(ctx); return _book.asStateFlow() }

    @Synchronized
    fun update(ctx: Context, change: (AdvanceBook) -> AdvanceBook) {
        load(ctx)
        val next = change(_book.value)
        if (next == _book.value) return
        _book.value = next
        prefs?.edit()?.putString(KEY, next.encode())?.apply()
    }

    @Synchronized
    private fun load(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        prefs = p
        _book.value = AdvanceBook.decode(p.getString(KEY, null))
    }
}
