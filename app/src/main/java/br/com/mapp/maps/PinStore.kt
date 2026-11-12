package br.com.mapp.maps

import android.content.Context
import android.content.SharedPreferences
import br.com.mapp.core.model.GeoPoint
import br.com.mapp.core.model.ZoneType
import br.com.mapp.core.pin.ZonePin
import br.com.mapp.core.pin.ZonePinCodec
import br.com.mapp.core.pin.ZonePins
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pins marcados na rua (📍 no painel do "M") esperando virar zona. Ficam no aparelho
 * (SharedPreferences), compartilhados entre o copiloto e o editor de zonas.
 */
object PinStore {
    private const val NAME = "mapp_pins"
    private const val KEY = "pins"

    private val _pins = MutableStateFlow<List<ZonePin>>(emptyList())
    private var prefs: SharedPreferences? = null

    fun pins(ctx: Context): StateFlow<List<ZonePin>> { load(ctx); return _pins.asStateFlow() }

    fun add(ctx: Context, type: ZoneType, point: GeoPoint, now: Long = System.currentTimeMillis()): ZonePin {
        val pin = ZonePin(now, type, point)
        update(ctx) { ZonePins.add(it, pin) }
        return pin
    }

    fun remove(ctx: Context, id: Long) = update(ctx) { ZonePins.remove(it, id) }

    @Synchronized
    private fun update(ctx: Context, change: (List<ZonePin>) -> List<ZonePin>) {
        load(ctx)
        val next = change(_pins.value)
        if (next == _pins.value) return
        _pins.value = next
        prefs?.edit()?.putString(KEY, ZonePinCodec.encode(next))?.apply()
    }

    @Synchronized
    private fun load(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        _pins.value = ZonePinCodec.decode(p.getString(KEY, null))
        prefs = p
    }
}
