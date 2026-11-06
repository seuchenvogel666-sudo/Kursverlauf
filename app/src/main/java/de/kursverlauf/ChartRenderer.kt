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

    private fun timeFmt(spanSec: Double) = SimpleDateFormat(
        when {
            spanSec < 30 * 3600 -> "HH:mm"
            spanSec < 9 * 86400 -> "E HH:mm"
            spanSec < 200 * 86400 -> "dd.MM."
            spanSec < 1100 * 86400 -> "MMM yy"
            else -> "yyyy"
        },
        Locale.GERMANY
    )

    fun draw(
        c: Canvas, w: Float, h: Float, d: Float,
        pts: List<Point>, o: Outlook?, col: ChartColors, range: Range, currency: String,
        title: String? = null, right: String? = null, rightColor: Int = col.text, bgCorner: Float = -1f,
        opts: ChartOpts = ChartOpts(), levels: Levels? = null, viewFrom: Float = 0f, viewTo: Float = 1f
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
        val fo: Outlook? = if (o != null && (opts.mid || opts.up || opts.down)) o else null
        val steps = if (fo != null) fo.up.size - 1 else 0
        val total = n + steps
        val i0 = (viewFrom.coerceIn(0f, 1f) * (total - 1)).toDouble()
        var i1 = (viewTo.coerceIn(0f, 1f) * (total - 1)).toDouble()
        if (i1 - i0 < 3.0) i1 = i0 + 3.0

        // sichtbarer Wertebereich
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        fun acc(x: Double) { if (x < lo) lo = x; if (x > hi) hi = x }
        val a0 = max(0, floor(i0).toInt()); val b0 = min(n - 1, ceil(i1).toInt())
        for (i in a0..b0) acc(pts[i].v)
        if (fo != null) for (k in 0..steps) {
            val idx = n - 1 + k
            if (idx >= floor(i0) && idx <= ceil(i1)) {
                if (opts.up) acc(fo.up[k].v)
                if (opts.down) acc(fo.down[k].v)
                if (opts.mid) fo.mid?.let { acc(it[k].v) }
            }
        }
        if (opts.baseline) acc(pts[0].v)
        if (opts.levels && levels != null) { levels.support?.let { acc(it.price) }; levels.resistance?.let { acc(it.price) } }
        if (lo > hi) { lo = pts.minOf { it.v }; hi = pts.maxOf { it.v } }
        if (hi - lo < 1e-12) { hi += 1.0; lo -= 1.0 }
        val pad = (hi - lo) * 0.06; lo -= pad; hi += pad
        val span = hi - lo
        val dec = when { span >= 20 -> 0; span >= 2 -> 1; span >= 0.2 -> 2; else -> 4 }

        fun tAt(idx: Double): Long {
            if (idx <= n - 1) {
                val i = floor(idx).toInt().coerceIn(0, n - 1); val j = min(n - 1, i + 1); val f = idx - i
                return (pts[i].t + (pts[j].t - pts[i].t) * f).toLong()
            }
            if (fo == null) return pts.last().t
            val k = idx - (n - 1)
            val i = floor(k).toInt().coerceIn(0, steps); val j = min(steps, i + 1); val f = k - i
            return (fo.up[i].t + (fo.up[j].t - fo.up[i].t) * f).toLong()
        }
        val tf = timeFmt((tAt(i1) - tAt(i0)).toDouble())

        p.textSize = 9f * d
        val yw = p.measureText(fmt(hi, dec)) + 6f * d
        val x0 = yw + 16f * d
        val x1 = w - 8f * d
        val y0 = (if (title != null) 24f else 8f) * d
        val y1 = h - 30f * d
        fun X(i: Double) = x0 + (x1 - x0) * ((i - i0) / (i1 - i0)).toFloat()
        fun Y(v: Double) = y1 - (y1 - y0) * ((v - lo) / span).toFloat()

        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (col.text and 0xFFFFFF) or 0x33000000; strokeWidth = 1f }
        p.color = col.text; p.textAlign = Paint.Align.RIGHT
        for (k in 0..4) {
            val v = lo + span * k / 4
            val y = Y(v)
            c.drawLine(x0, y, x1, y, grid)
            c.drawText(fmt(v, dec), x0 - 4f * d, y + 3f * d, p)
        }
        p.textAlign = Paint.Align.CENTER
        for (k in 0..4) {
            val idx = i0 + (i1 - i0) * k / 4
            val x = X(idx)
            c.drawLine(x, y0, x, y1, grid)
            val label = tf.format(Date(tAt(idx) * 1000))
            val half = p.measureText(label) / 2
            c.drawText(label, x.coerceIn(half, w - half), y1 + 12f * d, p)
        }
        p.textSize = 9f * d; p.textAlign = Paint.Align.CENTER
        c.drawText("Zeit (" + range.label + (if (fo != null) " + Prognose" else "") + ")", (x0 + x1) / 2, h - 4f * d, p)
        c.save(); c.rotate(-90f, 9f * d, (y0 + y1) / 2)
        c.drawText("Kurs" + (if (currency.isNotEmpty()) " ($currency)" else ""), 9f * d, (y0 + y1) / 2 + 3f * d, p)
        c.restore()

        c.save()
        c.clipRect(x0, y0, x1, y1)

        // Ausgangspunkt des angezeigten Zeitraums: graue Linie über die ganze Breite
        if (opts.baseline) {
            val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xFF9E9E9E.toInt()
                pathEffect = DashPathEffect(floatArrayOf(2f * d, 4f * d), 0f)
            }
            val yb = Y(pts[0].v)
            c.drawLine(x0, yb, x1, yb, bp)
        }

        if (fo != null) {
            val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f * d
                pathEffect = DashPathEffect(floatArrayOf(8f * d, 5f * d), 0f)
            }
            fun path(list: List<Point>) = Path().apply {
                list.forEachIndexed { k, pt -> if (k == 0) moveTo(X(n - 1.0), Y(pt.v)) else lineTo(X(n - 1.0 + k), Y(pt.v)) }
            }
            if (opts.up && opts.down) {
                val cone = Path().apply {
                    moveTo(X(n - 1.0), Y(fo.up[0].v))
                    fo.up.forEachIndexed { k, pt -> lineTo(X(n - 1.0 + k), Y(pt.v)) }
                    for (k in fo.down.indices.reversed()) lineTo(X(n - 1.0 + k), Y(fo.down[k].v))
                    close()
                }
                p.style = Paint.Style.FILL; p.color = (col.text and 0xFFFFFF) or 0x1A000000; c.drawPath(cone, p)
            }
            if (opts.up) { dashed.color = col.up; c.drawPath(path(fo.up), dashed) }
            if (opts.down) { dashed.color = col.down; c.drawPath(path(fo.down), dashed) }
            if (opts.mid) fo.mid?.let { mp ->
                dashed.color = col.mid; dashed.strokeWidth = 2.5f * d
                dashed.pathEffect = DashPathEffect(floatArrayOf(3f * d, 4f * d), 0f)
                c.drawPath(path(mp), dashed)
            }
            val xv = X(n - 1.0)
            val dv = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col.text; alpha = 90; strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f) }
            c.drawLine(xv, y0, xv, y1, dv)
        }

        // Unterstützung / Widerstand: weiß, gestrichelt, nur über den relevanten Bereich
        if (opts.levels && levels != null) {
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.WHITE
                pathEffect = DashPathEffect(floatArrayOf(10f * d, 6f * d), 0f)
            }
            val ei = n - 1.0 + (if (steps > 0) steps * 0.7 else 0.0)
            fun lvl(l: Level, name: String, above: Boolean) {
                val yv = Y(l.price)
                val xs = X(l.startIdx.toDouble())
                val xe = X(ei)
                c.drawLine(xs, yv, xe, yv, lp)
                val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT }
                val txt = name + " " + fmt(l.price, dec)
                val tx = max(xs, x0) + 4f * d
                val ty = if (above) yv - 5f * d else yv + 13f * d
                tp.style = Paint.Style.STROKE; tp.strokeWidth = 3f * d; tp.color = Color.BLACK; c.drawText(txt, tx, ty, tp)
                tp.style = Paint.Style.FILL; tp.color = Color.WHITE; c.drawText(txt, tx, ty, tp)
            }
            levels.resistance?.let { lvl(it, "Widerstand", true) }
            levels.support?.let { lvl(it, "Unterstützung", false) }
        }

        // Verlauf
        val hp = Path()
        val sIdx = max(0, floor(i0).toInt() - 1); val eIdx = min(n - 1, ceil(i1).toInt() + 1)
        for (i in sIdx..eIdx) { if (i == sIdx) hp.moveTo(X(i.toDouble()), Y(pts[i].v)) else hp.lineTo(X(i.toDouble()), Y(pts[i].v)) }
        val pl = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = col.price; strokeJoin = Paint.Join.ROUND }
        c.drawPath(hp, pl)
        c.restore()
    }
}
