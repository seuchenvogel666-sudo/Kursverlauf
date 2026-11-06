package de.kursverlauf

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.*
import kotlinx.coroutines.asCoroutineDispatcher

/** Angaben zur gelesenen Historie: Anzahl Kurse, Zeitraum (Unix-Sekunden), Lesezeitpunkt (ms), Art der Kurse. */
class HistMeta(val bars: Int, val from: Long, val to: Long, val loadedAt: Long, val unit: String, val fallback: Boolean)

/** Ergebnis der Musteranalyse. Alle Kurswerte relativ zum aktuellen Kurs (0.05 = +5 %). */
class Analysis(
    val upRel: DoubleArray, val downRel: DoubleArray, val medRel: DoubleArray,
    val horizonSec: Long, val patternBars: Int, val barSec: Long,
    val lossThr: Double, val pUp: Double, val pUpRaw: Double, val pLoss: Double,
    val analogs: Int, val hitRate: Double, val tests: Int,
    val blend: Double, val sigmaBar: Double,
    val reduced: Boolean, val meta: HistMeta
)

/**
 * Analogmethode mit Realitätsprüfung:
 *  1. Das aktuelle Kursmuster wird in drei Längen mit allen Abschnitten der Historie verglichen.
 *  2. Die ähnlichsten Abschnitte zeigen, was danach passiert ist (Roh-Prognose).
 *  3. Ein Rückwärts-Test misst die Trefferquote bei diesem Wert.
 *  4. Die Roh-Prognose wird je nach Trefferquote mit einem nüchternen Zufallsmodell (aktuelle Schwankung, keine Drift)
 *     gemischt und zusätzlich auf einen plausiblen Korridor begrenzt. Extreme Ausreißer aus der Vergangenheit
 *     (z. B. Kursexplosionen) können die Prognose so nicht mehr davontragen.
 */
object Analog {
    private const val P = 40
    private const val K = 30
    private const val MAX_BARS = 9000
    private const val FRESH_MS = 3_600_000L

    private class Entry(val a: Analysis, val ts: Long)
    private class Hist(val c: DoubleArray, val meta: HistMeta, val barSec: Long, val spanDays: Double)
    private class Pred(val up: DoubleArray?, val down: DoubleArray?, val med: DoubleArray?, val pUp: Double, val pLoss: Double, val n: Int)

    /** Eigener Thread mit niedriger Priorität: die Rechnung bremst das Scrollen nicht. */
    val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "analyse").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }.asCoroutineDispatcher()

    private val cache = ConcurrentHashMap<String, Entry>()
    private val hist = ConcurrentHashMap<String, Hist>()
    private val lastMeta = ConcurrentHashMap<String, HistMeta>()
    private val reasons = ConcurrentHashMap<String, String>()
    private val inflight = ConcurrentHashMap.newKeySet<String>()

    // (Musterlänge in Tagen, Horizont in Tagen, Schwelle für "Verlust")
    private fun cfg(r: Range) = when (r) {
        Range.D1 -> Triple(10, 1, 0.02)
        Range.W1 -> Triple(15, 5, 0.03)
        Range.M1 -> Triple(30, 21, 0.05)
        Range.M6 -> Triple(45, 42, 0.07)
        Range.Y1 -> Triple(60, 63, 0.10)
        Range.MAX -> Triple(120, 126, 0.15)
    }

    private fun key(s: String, r: Range, p: AnaPeriod) = "$s|${r.name}|${p.name}"
    fun cached(sym: String, r: Range, p: AnaPeriod): Analysis? = cache[key(sym, r, p)]?.a
    fun histMeta(sym: String, p: AnaPeriod): HistMeta? = lastMeta["$sym|${p.name}"]
    fun reason(sym: String, r: Range, p: AnaPeriod): String? = reasons[key(sym, r, p)]
    fun isFresh(sym: String, r: Range, p: AnaPeriod): Boolean =
        cache[key(sym, r, p)]?.let { System.currentTimeMillis() - it.ts < FRESH_MS } ?: false

    /** Manueller Neustart: alles verwerfen, die Historie wird neu gelesen. */
    fun clearAll() { cache.clear(); hist.clear(); lastMeta.clear(); reasons.clear() }

    /** Rechnet im Hintergrund und ruft danach done() auf. */
    fun warm(sym: String, r: Range, p: AnaPeriod, done: () -> Unit) {
        val k = key(sym, r, p)
        if (!inflight.add(k)) return
        thread {
            try { if (ensure(sym, r, p) != null) done() } catch (e: Exception) { } finally { inflight.remove(k) }
        }
    }

    private fun loadHist(sym: String, p: AnaPeriod, fallback: Boolean): Hist? {
        val interval = if (fallback) "60m" else p.interval
        val barSec = if (fallback) 3600L else p.barSec
        val unit = if (fallback) "Stundenkurse" else p.unit
        val days = if (fallback) 700 else p.days
        val hk = "$sym|$interval|$days"
        val nowMs = System.currentTimeMillis()
        val ttl = if (barSec >= 86400L) 6 * FRESH_MS else FRESH_MS
        hist[hk]?.let { if (nowMs - it.meta.loadedAt < ttl) return it }
        val now = nowMs / 1000
        val margin = if (interval == "1m") 3 else 0
        val from = if (days == 0) 0L else now - (days + margin) * 86400L
        var s = Api.chartPeriod(sym, from, now, interval).points.filter { it.v > 0 }
        if (days > 0 && s.isNotEmpty()) { val cut = s.last().t - days * 86400L; s = s.filter { it.t >= cut } }
        s = s.takeLast(MAX_BARS)
        if (s.size < 2) return null
        val h = Hist(
            DoubleArray(s.size) { s[it].v },
            HistMeta(s.size, s.first().t, s.last().t, nowMs, unit, fallback),
            barSec, max(1.0, (s.last().t - s.first().t) / 86400.0)
        )
        hist[hk] = h
        return h
    }

    /** Blockierend: Historie laden, analysieren, Ergebnis eine Stunde zwischenspeichern. */
    fun ensure(sym: String, r: Range, p: AnaPeriod, onProgress: (Int) -> Unit = {}): Analysis? {
        val k = key(sym, r, p)
        val mk = "$sym|${p.name}"
        val now = System.currentTimeMillis()
        cache[k]?.let { if (now - it.ts < FRESH_MS) return it.a }
        onProgress(0)
        reasons.remove(k)
        var a: Analysis? = null
        try {
            val h = loadHist(sym, p, false)
            if (h != null) lastMeta[mk] = h.meta
            onProgress(10)
            if (h != null) a = analyzeWith(h, r, p, onProgress, k)
            else reasons[k] = "Für diesen Zeitraum liegen keine Kurse vor"
            if (a == null && (h == null || h.c.size < 80)) {
                val f = try { loadHist(sym, p, true) } catch (e: Exception) { null }
                if (f != null) {
                    a = analyzeWith(f, r, p, onProgress, k)
                    if (a != null || h == null) lastMeta[mk] = f.meta
                }
            }
        } catch (e: Exception) {
            reasons[k] = "Historie konnte nicht gelesen werden (" + (e.message ?: e.javaClass.simpleName) + ")"
            return null
        }
        if (a == null) return null
        reasons.remove(k)
        cache[k] = Entry(a, now)
        onProgress(100)
        return a
    }

    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return if (x >= 0) y else -y
    }
    private fun phi(x: Double) = 0.5 * (1.0 + erf(x / sqrt(2.0)))

    private fun analyzeWith(h: Hist, r: Range, p: AnaPeriod, onProgress: (Int) -> Unit, rk: String): Analysis? {
        val (dL, dH, thr) = cfg(r)
        val c = h.c
        val m = c.size
        if (m < 80) { reasons[rk] = "Nur $m Kurse im Zeitraum „${p.label}“ (mindestens 80 nötig)"; return null }
        val perDay = if (h.barSec >= 86400L) 1.0 else m / h.spanDays
        val l0 = max(5, (dL * perDay).roundToInt())
        val h0 = max(1, (dH * perDay).roundToInt())
        var L = l0; var H = h0; var reduced = false
        if (m < l0 + h0 + 250) {
            reduced = true
            val s = min(1.0, (m - 60.0) / (l0 + h0))
            L = max(5, (l0 * s).toInt()); H = max(1, (h0 * s).toInt())
            if (m < L + H + 60) { reasons[rk] = "Historie ($m Kurse) zu kurz für Muster und Horizont"; return null }
        }
        val Ls = listOf(L / 2, L, L * 2).filter { it >= 5 && it * 2 + H + 120 <= m + (if (it == L) 10_000 else 0) }.distinct().ifEmpty { listOf(L) }
        val lc = DoubleArray(m) { ln(c[it]) }
        val now = m - 1
        val cur = predictEns(lc, now, Ls, H, thr, true)
        if (cur == null) { reasons[rk] = "Zu wenige ähnliche Muster in der Historie gefunden"; return null }

        onProgress(20)
        var tests = 0; var hits = 0; var iter = 0
        val step = max(H, 5)
        var t = now - H
        val minCands = min(150, m / 4)
        val minT = minCands + max(H, Ls.max()) + Ls.max()
        val work = m.toDouble() * Ls.sum()
        val maxTests = (110_000_000.0 / work).toInt().coerceIn(20, 60)
        val planned = if (t >= minT) min(maxTests, (t - minT) / step + 1) else 1
        while (iter < maxTests && t >= minT) {
            iter++
            onProgress(20 + 80 * iter / planned)
            val pr = predictEns(lc, t, Ls, H, thr, false)
            if (pr != null) {
                val predUp = pr.pUp >= 0.5
                val actualUp = lc[t + H] > lc[t]
                if (predUp == actualUp) hits++
                tests++
            }
            t -= step
        }
        val hit = if (tests >= 10) hits.toDouble() / tests else -1.0

        // Realitätsprüfung: Gewicht der Analogfälle richtet sich nach der Trefferquote
        val rel = if (hit < 0) 0.3 else ((hit - 0.5) / 0.1).coerceIn(0.15, 1.0)
        val alpha = 0.6 * rel
        val lv = min(120, m); val ls = min(max(L, 10), m)
        val sig = sqrt(0.5 * vol(lc, m - lv, lv).pow(2) + 0.5 * vol(lc, m - ls, ls).pow(2))
        fun qn(x: Double, z: Double) = exp(z * sig * sqrt(x)) - 1.0
        val up = DoubleArray(P + 1); val dn = DoubleArray(P + 1); val md = DoubleArray(P + 1)
        for (s in 0..P) {
            val x = s * H.toDouble() / P
            var mm = (alpha * cur.med!![s]).coerceIn(qn(x, -0.5), qn(x, 0.5))
            var u = min(alpha * cur.up!![s] + (1 - alpha) * qn(x, 0.84), qn(x, 1.28))
            var l = max(alpha * cur.down!![s] + (1 - alpha) * qn(x, -0.84), qn(x, -1.28))
            u = max(u, mm); l = min(l, mm)
            up[s] = u; dn[s] = l; md[s] = mm
        }
        val pUp = alpha * cur.pUp + (1 - alpha) * 0.5
        val pLossN = phi(ln(1.0 - thr) / (sig * sqrt(H.toDouble())))
        val pLoss = alpha * cur.pLoss + (1 - alpha) * pLossN
        return Analysis(
            up, dn, md, H * h.barSec, L, h.barSec,
            thr, pUp, cur.pUp, pLoss, cur.n, hit, tests, alpha, sig, reduced, h.meta
        )
    }

    private fun znorm(lc: DoubleArray, from: Int, L: Int, out: DoubleArray) {
        var s = 0.0
        for (i in 0 until L) s += lc[from + i]
        val mean = s / L
        var v = 0.0
        for (i in 0 until L) { val d = lc[from + i] - mean; v += d * d }
        val sd = max(sqrt(v / L), 1e-9)
        for (i in 0 until L) out[i] = (lc[from + i] - mean) / sd
    }

    private fun vol(lc: DoubleArray, from: Int, L: Int): Double {
        var s = 0.0; var s2 = 0.0
        val cnt = L - 1
        for (i in from until from + cnt) { val d = lc[i + 1] - lc[i]; s += d; s2 += d * d }
        val mu = s / cnt
        return max(sqrt(max(s2 / cnt - mu * mu, 0.0)), 1e-6)
    }

    private fun wq(vals: DoubleArray, w: DoubleArray, q: Double): Double {
        val idx = vals.indices.sortedBy { vals[it] }
        val total = w.sum()
        var cum = 0.0
        for (i in idx) { cum += w[i]; if (cum >= q * total) return vals[i] }
        return vals[idx.last()]
    }

    /** Mittel aus mehreren Musterlängen. */
    private fun predictEns(lc: DoubleArray, end: Int, Ls: List<Int>, H: Int, thr: Double, full: Boolean): Pred? {
        val preds = Ls.mapNotNull { predict(lc, end, it, H, thr, full) }
        if (preds.isEmpty()) return null
        val k = preds.size
        val pUp = preds.sumOf { it.pUp } / k
        val pLoss = preds.sumOf { it.pLoss } / k
        val n = preds.maxOf { it.n }
        if (!full) return Pred(null, null, null, pUp, pLoss, n)
        fun avg(sel: (Pred) -> DoubleArray) = DoubleArray(P + 1) { s -> preds.sumOf { sel(it)[s] } / k }
        return Pred(avg { it.up!! }, avg { it.down!! }, avg { it.med!! }, pUp, pLoss, n)
    }

    private fun predict(lc: DoubleArray, end: Int, L: Int, H: Int, thr: Double, full: Boolean): Pred? {
        val lastCand = end - max(H, L)
        if (lastCand < L - 1) return null
        val q = DoubleArray(L); znorm(lc, end - L + 1, L, q)
        val volNow = vol(lc, end - L + 1, L)
        val cnt = lastCand - (L - 1) + 1
        val dist = DoubleArray(cnt)
        val z = DoubleArray(L)
        for (i in 0 until cnt) {
            znorm(lc, i, L, z)
            var d = 0.0
            for (j in 0 until L) { val x = z[j] - q[j]; d += x * x }
            dist[i] = d / L
        }
        val minGap = max(3, L / 2)
        val pick = ArrayList<Int>()   // Index i = Beginn des Fensters
        val blocked = BooleanArray(cnt)
        while (pick.size < K) {
            var best = -1; var bd = Double.MAX_VALUE
            for (i in 0 until cnt) if (!blocked[i] && dist[i] < bd) { bd = dist[i]; best = i }
            if (best < 0) break
            pick.add(best)
            for (j in max(0, best - minGap + 1)..min(cnt - 1, best + minGap - 1)) blocked[j] = true
        }
        if (pick.size < 8) return null

        val n = pick.size
        // Ähnlichkeit zählt, jüngere Abschnitte etwas mehr
        val w = DoubleArray(n) { (1.0 / (0.25 + dist[pick[it]])) * (1.0 + 0.5 * pick[it].toDouble() / cnt) }
        val scale = DoubleArray(n) { (volNow / vol(lc, pick[it], L)).coerceIn(0.5, 2.0) }
        val e = IntArray(n) { pick[it] + L - 1 }

        fun fut(a: Int, x: Double): Double {
            val lo = x.toInt().coerceIn(0, H)
            val hi = min(lo + 1, H)
            val f = x - lo
            return ((lc[e[a] + lo] - lc[e[a]]) * (1 - f) + (lc[e[a] + hi] - lc[e[a]]) * f) * scale[a]
        }

        var wUp = 0.0; var wLoss = 0.0; var wAll = 0.0
        for (a in 0 until n) {
            val rel = exp(fut(a, H.toDouble())) - 1
            wAll += w[a]
            if (rel > 0) wUp += w[a]
            if (rel < -thr) wLoss += w[a]
        }
        if (!full) return Pred(null, null, null, wUp / wAll, wLoss / wAll, n)

        val up = DoubleArray(P + 1); val dn = DoubleArray(P + 1); val md = DoubleArray(P + 1)
        for (s in 0..P) {
            val x = s * H.toDouble() / P
            val vals = DoubleArray(n) { exp(fut(it, x)) - 1 }
            up[s] = wq(vals, w, 0.8); dn[s] = wq(vals, w, 0.2); md[s] = wq(vals, w, 0.5)
        }
        return Pred(up, dn, md, wUp / wAll, wLoss / wAll, n)
    }
}
