package de.kursverlauf

import android.graphics.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class ChartColors(val price: Int, val up: Int, val down: Int, val text: Int, val bg: Int, val mid: Int = Pal.MID)

/** Zeichnet den Graphen auf ein android.graphics.Canvas (App und Widget nutzen denselben Code). */
object ChartRenderer {
    fun fmt(v: Double, dec: Int? = null): String {
        val d = dec ?: when { abs(v) >= 1000 -> 0; abs(v) >= 1 -> 2; else -> 4 }
        return String.format(Locale.GERMANY, "%,.${d}f", v)
    }

    private fun timeFmt(r: Range) = SimpleDateFormat(
        when (r) { Range.D1 -> "HH:mm"; Range.W1 -> "E HH:mm"; Range.M1 -> "dd.MM."; Range.Y1 -> "MMM yy"; Range.MAX -> "yyyy" },
        Locale.GERMANY
    )

    fun draw(
        c: Canvas, w: Float, h: Float, d: Float,
        pts: List<Point>, o: Outlook?, col: ChartColors, range: Range, currency: String,
        title: String? = null, right: String? = null, rightColor: Int = col.text, bgCorner: Float = -1f
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (bgCorner >= 0f) { p.color = col.bg; c.drawRoundRect(0f, 0f, w, h, bgCorner, bgCorner, p) }
        if (title != null) {
            p.typeface = Typeface.DEFAULT_BOLD; p.textSize = 12f * d
            p.color = col.text; p.textAlign = Paint.Align.LEFT
            c.drawText(title, 8f * d, 16f * d, p)
            if (right != null) { p.color = rightColor; p.textAlign = Paint.Align.RIGHT; c.drawText(right, w - 8f * d, 16f * d, p) }
            p.typeface = Typeface.DEFAULT
        }
        if (pts.size < 2) {
            p.color = col.text; p.textSize = 12f * d; p.textAlign = Paint.Align.CENTER
            c.drawText("Keine Daten", w / 2, h / 2, p); return
        }
        val n = pts.size
        val steps = if (o != null) o.up.size - 1 else 0
        val total = n + steps
        var lo = pts.minOf { it.v }; var hi = pts.maxOf { it.v }
        if (o != null) { lo = min(lo, o.down.minOf { it.v }); hi = max(hi, o.up.maxOf { it.v }) }
        if (hi - lo < 1e-12) { hi += 1.0; lo -= 1.0 }
        val pad = (hi - lo) * 0.05; lo -= pad; hi += pad
        val span = hi - lo
        val dec = when { span >= 20 -> 0; span >= 2 -> 1; span >= 0.2 -> 2; else -> 4 }
        val dt = max(1L, (pts.last().t - pts.first().t) / (n - 1))
        val tf = timeFmt(range)

        p.textSize = 9f * d
        val yw = p.measureText(fmt(hi, dec)) + 6f * d
        val x0 = yw + 16f * d
        val x1 = w - 8f * d
        val y0 = (if (title != null) 24f else 8f) * d
        val y1 = h - 30f * d
        fun X(i: Int) = x0 + (x1 - x0) * i / (total - 1)
        fun Y(v: Double) = y1 - (y1 - y0) * ((v - lo) / span).toFloat()

        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (col.text and 0xFFFFFF) or 0x33000000; strokeWidth = 1f }
        // y-Achse: Gitter + Werte
        p.color = col.text; p.textAlign = Paint.Align.RIGHT
        for (k in 0..4) {
            val v = lo + span * k / 4
            val y = Y(v)
            c.drawLine(x0, y, x1, y, grid)
            c.drawText(fmt(v, dec), x0 - 4f * d, y + 3f * d, p)
        }
        // x-Achse: Zeit
        p.textAlign = Paint.Align.CENTER
        for (k in 0..4) {
            val i = ((total - 1) * k / 4.0).roundToInt()
            val t = if (i < n) pts[i].t else (o?.up?.get(i - n + 1)?.t ?: pts.last().t)
            val x = X(i)
            c.drawLine(x, y0, x, y1, grid)
            val label = tf.format(Date(t * 1000))
            val half = p.measureText(label) / 2
            c.drawText(label, x.coerceIn(half, w - half), y1 + 12f * d, p)
        }
        // Achsentitel
        p.textSize = 9f * d; p.textAlign = Paint.Align.CENTER
        c.drawText("Zeit (" + range.label + (if (o != null) " + Prognose" else "") + ")", (x0 + x1) / 2, h - 4f * d, p)
        c.save(); c.rotate(-90f, 9f * d, (y0 + y1) / 2)
        c.drawText("Kurs" + (if (currency.isNotEmpty()) " ($currency)" else ""), 9f * d, (y0 + y1) / 2 + 3f * d, p)
        c.restore()

        // Prognosekegel + Pfade
        if (o != null) {
            val cone = Path().apply {
                moveTo(X(n - 1), Y(o.up[0].v))
                o.up.forEachIndexed { k, pt -> lineTo(X(n - 1 + k), Y(pt.v)) }
                for (k in o.down.indices.reversed()) lineTo(X(n - 1 + k), Y(o.down[k].v))
                close()
            }
            p.style = Paint.Style.FILL; p.color = (col.text and 0xFFFFFF) or 0x1A000000; c.drawPath(cone, p)
            val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f * d
                pathEffect = DashPathEffect(floatArrayOf(8f * d, 5f * d), 0f)
            }
            fun path(list: List<Point>) = Path().apply {
                list.forEachIndexed { k, pt -> if (k == 0) moveTo(X(n - 1), Y(pt.v)) else lineTo(X(n - 1 + k), Y(pt.v)) }
            }
            ln.color = col.up; c.drawPath(path(o.up), ln)
            ln.color = col.down; c.drawPath(path(o.down), ln)
            o.mid?.let { mp ->
                ln.color = col.mid; ln.strokeWidth = 2.5f * d
                ln.pathEffect = DashPathEffect(floatArrayOf(3f * d, 4f * d), 0f)
                c.drawPath(path(mp), ln)
            }
            val xv = X(n - 1)
            val dv = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col.text; alpha = 90; strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f) }
            c.drawLine(xv, y0, xv, y1, dv)
        }
        // Verlauf
        val hp = Path()
        pts.forEachIndexed { i, pt -> if (i == 0) hp.moveTo(X(i), Y(pt.v)) else hp.lineTo(X(i), Y(pt.v)) }
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = col.price; strokeJoin = Paint.Join.ROUND }
        c.drawPath(hp, lp)
    }
}
