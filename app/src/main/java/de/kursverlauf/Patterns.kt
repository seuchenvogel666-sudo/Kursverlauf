package de.kursverlauf

import kotlin.math.*

class Pattern(val name: String, val meaning: String)

/** Erkennt klassische Chartmuster im sichtbaren Verlauf (Zickzack-Wendepunkte + Regeln). */
object Patterns {
    private class Pv(val i: Int, val y: Double, val high: Boolean)

    fun detect(p: List<Point>): Pattern? {
        if (p.size < 20) return null
        val m = min(80, p.size)
        val v = DoubleArray(m) { i ->
            val a = i * p.size / m
            val b = max(a + 1, (i + 1) * p.size / m)
            var s = 0.0
            for (k in a until b) s += p[k].v
            s / (b - a)
        }
        var lo = v[0]; var hi = v[0]
        for (x in v) { if (x < lo) lo = x; if (x > hi) hi = x }
        val rng = hi - lo
        if (rng <= abs(hi) * 1e-9) return Pattern("Seitwärtsphase", "Der Kurs bewegt sich praktisch nicht.")
        val y = DoubleArray(m) { (v[it] - lo) / rng }

        // Zickzack-Wendepunkte (Schwelle 14 % der Spanne)
        val th = 0.14
        val pv = ArrayList<Pv>()
        var up: Boolean? = null
        var ci = 0; var cv = y[0]
        for (i in 1 until m) {
            val x = y[i]
            when (up) {
                null -> {
                    if (x - y[0] >= th) { pv.add(Pv(0, y[0], false)); up = true; ci = i; cv = x }
                    else if (y[0] - x >= th) { pv.add(Pv(0, y[0], true)); up = false; ci = i; cv = x }
                }
                true -> {
                    if (x > cv) { ci = i; cv = x }
                    else if (cv - x >= th) { pv.add(Pv(ci, cv, true)); up = false; ci = i; cv = x }
                }
                false -> {
                    if (x < cv) { ci = i; cv = x }
                    else if (x - cv >= th) { pv.add(Pv(ci, cv, false)); up = true; ci = i; cv = x }
                }
            }
        }
        if (up != null) pv.add(Pv(ci, cv, up))
        val k = pv.size
        fun seq(vararg h: Boolean) = k >= h.size && h.indices.all { pv[k - h.size + it].high == h[it] }

        // Schulter-Kopf-Schulter
        if (seq(true, false, true, false, true)) {
            val a = pv[k - 5]; val hd = pv[k - 3]; val e = pv[k - 1]
            if (hd.y > a.y + 0.08 && hd.y > e.y + 0.08 && abs(a.y - e.y) < 0.15)
                return Pattern("Schulter-Kopf-Schulter", "Klassisches Umkehrmuster nach oben: kann auf fallende Kurse hindeuten.")
        }
        if (seq(false, true, false, true, false)) {
            val a = pv[k - 5]; val hd = pv[k - 3]; val e = pv[k - 1]
            if (hd.y < a.y - 0.08 && hd.y < e.y - 0.08 && abs(a.y - e.y) < 0.15)
                return Pattern("Umgekehrte Schulter-Kopf-Schulter", "Klassisches Umkehrmuster nach unten: kann auf steigende Kurse hindeuten.")
        }
        // Doppeltop / Doppelboden
        if (seq(true, false, true)) {
            val h1 = pv[k - 3]; val l = pv[k - 2]; val h2 = pv[k - 1]
            if (abs(h1.y - h2.y) < 0.10 && min(h1.y, h2.y) - l.y > 0.25 && h2.y > 0.7)
                return Pattern("Doppeltop", "Zwei ähnlich hohe Gipfel: Umkehrmuster, kann auf fallende Kurse hindeuten.")
        }
        if (seq(false, true, false)) {
            val l1 = pv[k - 3]; val h = pv[k - 2]; val l2 = pv[k - 1]
            if (abs(l1.y - l2.y) < 0.10 && h.y - max(l1.y, l2.y) > 0.25 && l2.y < 0.3)
                return Pattern("Doppelboden", "Zwei ähnlich tiefe Tiefpunkte: Umkehrmuster, kann auf steigende Kurse hindeuten.")
        }
        // Dreiecke
        val recent = pv.takeLast(6)
        val hs = recent.filter { it.high }; val ls = recent.filter { !it.high }
        if (hs.size >= 2 && ls.size >= 2) {
            val dh = hs.last().y - hs.first().y
            val dl = ls.last().y - ls.first().y
            val w0 = hs.first().y - ls.first().y
            val w1 = hs.last().y - ls.last().y
            if (w0 > 0.2 && w1 < 0.7 * w0) {
                if (abs(dh) < 0.08 && dl > 0.08)
                    return Pattern("Aufsteigendes Dreieck", "Flacher Widerstand, steigende Tiefs: setzt sich oft nach oben fort, der Ausbruch entscheidet.")
                if (abs(dl) < 0.08 && dh < -0.08)
                    return Pattern("Absteigendes Dreieck", "Flache Unterstützung, fallende Hochs: setzt sich oft nach unten fort, der Ausbruch entscheidet.")
                if (dh < -0.06 && dl > 0.06)
                    return Pattern("Symmetrisches Dreieck", "Die Schwankung wird enger: ein Ausbruch in eine Richtung steht bevor, welche ist offen.")
            }
        }
        // Trend (lineare Regression)
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (i in 0 until m) { sx += i; sy += y[i]; sxx += i.toDouble() * i; sxy += i * y[i]; syy += y[i] * y[i] }
        val den = m * sxx - sx * sx
        val slope = (m * sxy - sx * sy) / den
        val varY = m * syy - sy * sy
        val r2 = if (varY <= 0) 0.0 else (m * sxy - sx * sy).pow(2) / (den * varY)
        val drift = slope * (m - 1)
        if (r2 > 0.65 && drift > 0.45) return Pattern("Aufwärtstrend", "Steigende Hochs und Tiefs: der Trend zeigt nach oben.")
        if (r2 > 0.65 && drift < -0.45) return Pattern("Abwärtstrend", "Fallende Hochs und Tiefs: der Trend zeigt nach unten.")
        // Ausbruch
        val early = y.take(m * 5 / 6)
        val eMax = early.max(); val eMin = early.min()
        if (y.last() >= 0.97 && eMax <= 0.88) return Pattern("Ausbruch nach oben", "Der Kurs verlässt seine bisherige Spanne nach oben und setzt ein neues Hoch.")
        if (y.last() <= 0.03 && eMin >= 0.12) return Pattern("Ausbruch nach unten", "Der Kurs verlässt seine bisherige Spanne nach unten und setzt ein neues Tief.")
        if (r2 > 0.5 && drift > 0.2) return Pattern("Schwacher Aufwärtstrend", "Leichter Aufwärtstrend mit Rücksetzern.")
        if (r2 > 0.5 && drift < -0.2) return Pattern("Schwacher Abwärtstrend", "Leichter Abwärtstrend mit Erholungen.")
        if (abs(drift) < 0.25) return Pattern("Seitwärtsphase", "Der Kurs pendelt in einer Spanne ohne klare Richtung.")
        return Pattern("Kein klares Muster", "Der Verlauf passt zu keinem der bekannten Standardmuster.")
    }
}
