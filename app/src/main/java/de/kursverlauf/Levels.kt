package de.kursverlauf

import kotlin.math.*

class Level(val price: Double, val startIdx: Int, val touches: Int)
class Levels(val support: Level?, val resistance: Level?)

/** Nächste Unterstützung (unter dem Kurs) und nächster Widerstand (darüber) aus gehäuften Wendepunkten. */
object LevelFinder {
    fun find(p: List<Point>): Levels {
        val n = p.size
        if (n < 20) return Levels(null, null)
        val v = DoubleArray(n) { p[it].v }
        val last = v[n - 1]
        var lo = v[0]; var hi = v[0]; var loI = 0; var hiI = 0
        for (i in 0 until n) { if (v[i] < lo) { lo = v[i]; loI = i }; if (v[i] > hi) { hi = v[i]; hiI = i } }
        val w = max(2, n / 30)
        val tol = max(last * 0.004, (hi - lo) * 0.02)

        class Pv(val i: Int, val price: Double)
        val piv = ArrayList<Pv>()
        for (i in w until n - w) {
            var isHi = true; var isLo = true
            for (k in i - w..i + w) {
                if (v[k] > v[i]) isHi = false
                if (v[k] < v[i]) isLo = false
                if (!isHi && !isLo) break
            }
            if (isHi || isLo) piv.add(Pv(i, v[i]))
        }
        piv.sortBy { it.price }
        // Cluster: [Summe, Anzahl, frühester Index, letzter Preis]
        val cs = ArrayList<DoubleArray>()
        for (pv in piv) {
            val c = cs.lastOrNull()
            if (c != null && pv.price - c[3] <= tol) {
                c[0] += pv.price; c[1] += 1.0; c[2] = min(c[2], pv.i.toDouble()); c[3] = pv.price
            } else cs.add(doubleArrayOf(pv.price, 1.0, pv.i.toDouble(), pv.price))
        }
        val levels = cs.map { Level(it[0] / it[1], it[2].toInt(), it[1].toInt()) }
        val below = levels.filter { it.price < last - tol * 0.5 }.sortedByDescending { it.price }
        val above = levels.filter { it.price > last + tol * 0.5 }.sortedBy { it.price }
        val sup = below.firstOrNull { it.touches >= 2 } ?: below.firstOrNull() ?: (if (lo < last - tol * 0.5) Level(lo, loI, 1) else null)
        val res = above.firstOrNull { it.touches >= 2 } ?: above.firstOrNull() ?: (if (hi > last + tol * 0.5) Level(hi, hiI, 1) else null)
        return Levels(sup, res)
    }
}
