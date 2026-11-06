package de.kursverlauf

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.*
import kotlinx.coroutines.asCoroutineDispatcher

/** Angaben zur gelesenen Historie: Anzahl Kurse, Zeitraum (Unix-Sekunden), Lesezeitpunkt (ms). */
class HistMeta(val bars: Int, val from: Long, val to: Long, val loadedAt: Long, val hourly: Boolean)

/** Ergebnis der Musteranalyse. Alle Kurswerte relativ zum aktuellen Kurs (0.05 = +5 %). */
class Analysis(
    val upRel: DoubleArray, val downRel: DoubleArray, val medRel: DoubleArray,
    val horizonSec: Long, val patternBars: Int, val barSec: Long,
    val lossThr: Double, val pUp: Double, val pLoss: Double,
    val analogs: Int, val hitRate: Double, val tests: Int,
    val reduced: Boolean, val meta: HistMeta
)

/**
 * Analogmethode: Das aktuelle Kursmuster wird mit allen Abschnitten der verfügbaren Historie verglichen.
 * Die ähnlichsten Abschnitte zeigen, was danach passiert ist. Daraus entstehen Prozentwerte und Pfade.
 * Ein Rückwärts-Test misst, wie oft die Richtung bei diesem Wert gestimmt hätte.
 * Ist die Tageshistorie kurz, werden Musterlänge und Horizont angepasst; reicht sie gar nicht,
 * wird auf Stundenkurse ausgewichen.
 */
object Analog {
    private const val P = 40
    private const val K = 25
    private const val MAX_BARS = 3500
    private const val FRESH_MS = 3_600_000L

    private class Entry(val a: Analysis, val ts: Long)
    private class Hist(val c: DoubleArray, val meta: HistMeta, val barSec: Long)
    private class Pred(val up: DoubleArray?, val down: DoubleArray?, val med: DoubleArray?, val pUp: Double, val pLoss: Double, val n: Int)

    /** Eigener Thread mit niedriger Priorität: die Rechnung bremst das Scrollen nicht. */
    val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "analyse").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }.asCoroutineDispatcher()

    private val cache = ConcurrentHashMap<String, Entry>()
    private val hist = ConcurrentHashMap<String, Hist>()
    private val lastMeta = ConcurrentHashMap<String, HistMeta>()
    private val inflight = ConcurrentHashMap.newKeySet<String>()

    // (Musterlänge in Tagen, Horizont in Tagen, Schwelle für "Verlust")
    private fun cfg(r: Range) = when (r) {
        Range.D1 -> Triple(10, 1, 0.02)
        Range.W1 -> Triple(15, 5, 0.03)
        Range.M1 -> Triple(30, 21, 0.05)
        Range.Y1 -> Triple(60, 63, 0.10)
        Range.MAX -> Triple(120, 126, 0.15)
    }

    private fun key(s: String, r: Range) = "$s|${r.name}"
    fun cached(sym: String, r: Range): Analysis? = cache[key(sym, r)]?.a
    fun histMeta(sym: String): HistMeta? = lastMeta[sym]
    fun isFresh(sym: String, r: Range): Boolean =
        cache[key(sym, r)]?.let { System.currentTimeMillis() - it.ts < FRESH_MS } ?: false

    /** Rechnet im Hintergrund und ruft danach done() auf. */
    fun warm(sym: String, r: Range, done: () -> Unit) {
        val k = key(sym, r)
        if (!inflight.add(k)) return
        thread {
            try { if (ensure(sym, r) != null) done() } catch (e: Exception) { } finally { inflight.remove(k) }
        }
    }

    private fun loadHist(sym: String, hourly: Boolean): Hist? {
        val hk = sym + if (hourly) "|h" else "|d"
        val now = System.currentTimeMillis()
        hist[hk]?.let { if (now - it.meta.loadedAt < 6 * FRESH_MS) return it }
        val s = (if (hourly) Api.chartRaw(sym, "2y", "60m") else Api.history(sym))
            .points.filter { it.v > 0 }.takeLast(MAX_BARS)
        if (s.size < 2) return null
        val h = Hist(
            DoubleArray(s.size) { s[it].v },
            HistMeta(s.size, s.first().t, s.last().t, now, hourly),
            if (hourly) 3600L else 86400L
        )
        hist[hk] = h
        return h
    }

    /** Blockierend: Historie laden, analysieren, Ergebnis eine Stunde zwischenspeichern. */
    fun ensure(sym: String, r: Range, onProgress: (Int) -> Unit = {}): Analysis? {
        val k = key(sym, r)
        val now = System.currentTimeMillis()
        cache[k]?.let { if (now - it.ts < FRESH_MS) return it.a }
        onProgress(0)
        val daily = loadHist(sym, false)
        if (daily != null) lastMeta[sym] = daily.meta
        onProgress(10)
        var a = daily?.let { analyzeWith(it, r, onProgress) }
        if (a == null) {
            val hourly = try { loadHist(sym, true) } catch (e: Exception) { null }
            if (hourly != null) {
                a = analyzeWith(hourly, r, onProgress)
                if (a != null || daily == null) lastMeta[sym] = hourly.meta
            }
        }
        if (a == null) return null
        cache[k] = Entry(a, now)
        onProgress(100)
        return a
    }

    private fun analyzeWith(h: Hist, r: Range, onProgress: (Int) -> Unit): Analysis? {
        val (d0L, d0H, thr) = cfg(r)
        val mult = if (h.meta.hourly) 24 else 1
        val l0 = d0L * mult
        val h0 = d0H * mult
        val c = h.c
        val m = c.size
        var L = l0
        var H = h0
        var reduced = false
        if (m < l0 + h0 + 250) {
            if (m < 80) return null
            reduced = true
            val s = min(1.0, (m - 60.0) / (l0 + h0))
            L = max(5, (l0 * s).toInt())
            H = max(1, (h0 * s).toInt())
            if (m < L + H + 60) return null
        }
        val lc = DoubleArray(m) { ln(c[it]) }
        val now = m - 1
        val cur = predict(lc, now, L, H, thr, true) ?: return null

        onProgress(20)
        var tests = 0; var hits = 0; var iter = 0
        val step = max(H, 5)
        var t = now - H
        val minCands = min(150, m / 4)
        val minT = minCands + max(H, L) + L
        val planned = if (t >= minT) min(60, (t - minT) / step + 1) else 1
        while (iter < 60 && t >= minT) {
            iter++
            onProgress(20 + 80 * iter / planned)
            val pr = predict(lc, t, L, H, thr, false)
            if (pr != null) {
                val predUp = pr.pUp >= 0.5
                val actualUp = lc[t + H] > lc[t]
                if (predUp == actualUp) hits++
                tests++
            }
            t -= step
        }
        val hit = if (tests >= 10) hits.toDouble() / tests else -1.0
        return Analysis(
            cur.up!!, cur.down!!, cur.med!!, H * h.barSec, L, h.barSec,
            thr, cur.pUp, cur.pLoss, cur.n, hit, tests, reduced, h.meta
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
        val w = DoubleArray(n) { 1.0 / (0.25 + dist[pick[it]]) }
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
