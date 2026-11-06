package de.kursverlauf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

const val WHITE = -1
val BLACK = 0xFF000000.toInt()

/** Durchsichtigkeit in Prozent: 0 = deckend, 100 = vollständig durchsichtig. */
fun withTransparency(color: Int, transparentPct: Int): Int {
    val a = ((100 - transparentPct.coerceIn(0, 100)) * 255 / 100)
    return (color and 0xFFFFFF) or (a shl 24)
}

fun textColorFor(bg: Int): Int {
    val r = (bg shr 16) and 0xFF; val g = (bg shr 8) and 0xFF; val b = bg and 0xFF
    return if ((0.299 * r + 0.587 * g + 0.114 * b) / 255 > 0.6) BLACK else WHITE
}

class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("kv", Context.MODE_PRIVATE)

    var appBg: Int get() = sp.getInt("appBg", Pal.BG); set(v) = sp.edit().putInt("appBg", v).apply()
    var appTransp: Int get() = sp.getInt("appTr", 0); set(v) = sp.edit().putInt("appTr", v).apply()
    var widgetBg: Int get() = sp.getInt("wBg", Pal.BG); set(v) = sp.edit().putInt("wBg", v).apply()
    var widgetTransp: Int get() = sp.getInt("wTr", 15); set(v) = sp.edit().putInt("wTr", v).apply()
    var range: Range
        get() = runCatching { Range.valueOf(sp.getString("range", "D1")!!) }.getOrDefault(Range.D1)
        set(v) = sp.edit().putString("range", v.name).apply()

    var watch: List<Watch>
        get() {
            val s = sp.getString("watch", null) ?: return listOf(Watch("BTC-USD", "Bitcoin"), Watch("AAPL", "Apple Inc."))
            val a = JSONArray(s)
            return (0 until a.length()).map { val o = a.getJSONObject(it); Watch(o.getString("s"), o.getString("n")) }
        }
        set(l) {
            val a = JSONArray()
            l.forEach { a.put(JSONObject().put("s", it.symbol).put("n", it.name)) }
            sp.edit().putString("watch", a.toString()).apply()
        }

    fun isCollapsed(sym: String): Boolean = (sp.getStringSet("collapsed", emptySet()) ?: emptySet()).contains(sym)
    fun setCollapsed(sym: String, c: Boolean) {
        val s = HashSet(sp.getStringSet("collapsed", emptySet()) ?: emptySet())
        if (c) s.add(sym) else s.remove(sym)
        sp.edit().putStringSet("collapsed", s).apply()
    }

    var sectionOrder: List<String>
        get() = sp.getString("secOrder", null)?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_ORDER
        set(v) = sp.edit().putString("secOrder", v.joinToString(",")).apply()

    fun widgetSymbol(id: Int): String? = sp.getString("ws_$id", null)
    fun widgetRange(id: Int): Range = runCatching { Range.valueOf(sp.getString("wr_$id", "D1")!!) }.getOrDefault(Range.D1)
    fun setWidget(id: Int, symbol: String, r: Range) { sp.edit().putString("ws_$id", symbol).putString("wr_$id", r.name).apply() }
    fun removeWidget(id: Int) { sp.edit().remove("ws_$id").remove("wr_$id").apply() }

    companion object {
        val DEFAULT_ORDER = listOf("pattern", "tendency", "recommendation", "updown", "basis", "hit", "range", "loss", "history", "kenn")
    }
}
