package de.kursverlauf

import java.util.Locale
import kotlin.math.*

class Outlook(val up: List<Point>, val down: List<Point>, val label: String, val detail: String, val score: Int)

/**
 * Statistische Szenarien aus den sichtbaren Kursdaten (keine Anlageberatung):
 *  - Positiv-/Negativ-Pfad: Drift und Schwankung der bisherigen Renditen, oberes/unteres ~80-%-Quantil
 *  - Einschätzung: Trend (kurzer/langer Durchschnitt), RSI und Momentum ergeben einen Punktwert
 */
object Forecast {
    fun compute(p: List<Point>): Outlook? {
        val n = p.size
        if (n < 12 || p.any { it.v <= 0.0 }) return null
        val r = DoubleArray(n - 1) { ln(p[it + 1].v / p[it].v) }
        val mu = r.average()
        val sd = sqrt(r.sumOf { (it - mu) * (it - mu) } / (r.size - 1))
        val dt = max(1L, (p.last().t - p.first().t) / (n - 1))
        val h = max(6, n / 4)
        val last = p.last()
        val z = 0.84
        val up = (0..h).map { k -> Point(last.t + k * dt, last.v * exp(mu * k + z * sd * sqrt(k.toDouble()))) }
        val down = (0..h).map { k -> Point(last.t + k * dt, last.v * exp(mu * k - z * sd * sqrt(k.toDouble()))) }

        val s = max(3, n / 10)
        val l = max(s + 2, n / 3)
        fun sma(len: Int) = p.takeLast(len).map { it.v }.average()
        val trend = if (sma(s) >= sma(l)) 1 else -1

        var g = 0.0; var ls = 0.0
        for (x in r.takeLast(min(14, r.size))) if (x > 0) g += x else ls -= x
        val rsi = if (ls == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + g / ls)
        val rsiScore = when { rsi < 30 -> 1; rsi > 70 -> -1; else -> 0 }

        val mom = last.v / p[n - 1 - s].v - 1.0
        val score = trend + rsiScore + (if (mom > 0) 1 else -1)

        val label = when {
            score >= 2 -> "Tendenz: eher Kauf-Chance"
            score <= -2 -> "Tendenz: eher Verkaufs-Risiko"
            else -> "Tendenz: neutral, abwarten"
        }
        val detail = String.format(
            Locale.GERMANY,
            "Trend %s · RSI %.0f · Momentum %+.1f %% · Szenarien am Ende: %+.1f %% / %+.1f %%",
            if (trend > 0) "aufwärts" else "abwärts", rsi, mom * 100,
            (up.last().v / last.v - 1) * 100, (down.last().v / last.v - 1) * 100
        )
        return Outlook(up, down, label, detail, score)
    }
}
