package de.kursverlauf

import java.util.Locale
import kotlin.math.*

class Outlook(
    val up: List<Point>, val down: List<Point>, val label: String, val detail: String, val score: Int,
    val pUp: Double? = null, val lines: List<String> = emptyList()
)

object Forecast {

    /** Einfache Variante (Fallback, solange die Historie noch nicht analysiert ist). */
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
            "Einfache Schätzung (Historie wird noch analysiert) · Trend %s · RSI %.0f · Momentum %+.1f %%",
            if (trend > 0) "aufwärts" else "abwärts", rsi, mom * 100
        )
        return Outlook(up, down, label, detail, score)
    }

    /** Prognose aus der Musteranalyse: Szenarien und Prozentwerte stammen aus ähnlichen historischen Verläufen. */
    fun fromAnalysis(p: List<Point>, a: Analysis): Outlook? {
        val n = p.size
        if (n < 2) return null
        val h = max(6, n / 4)
        val last = p.last()
        val span = a.horizonDays * 86400L

        fun at(arr: DoubleArray, i: Int): Double {
            val pos = i.toDouble() / h * (arr.size - 1)
            val lo = floor(pos).toInt().coerceIn(0, arr.size - 1)
            val hi = min(arr.size - 1, lo + 1)
            val f = pos - lo
            return arr[lo] * (1 - f) + arr[hi] * f
        }
        val up = (0..h).map { i -> Point(last.t + span * i / h, last.v * (1 + at(a.upRel, i))) }
        val down = (0..h).map { i -> Point(last.t + span * i / h, last.v * (1 + at(a.downRel, i))) }

        val pctUp = a.pUp * 100
        val weak = a.hitRate < 0 || a.hitRate < 0.53
        val base = when {
            a.pUp >= 0.60 -> "Tendenz: eher Kauf-Chance"
            a.pUp <= 0.40 -> "Tendenz: eher Verkaufs-Risiko"
            else -> "Tendenz: neutral, abwarten"
        }
        val label = base + if (a.hitRate < 0) " (Aussagekraft unbekannt)" else if (weak) " (geringe Aussagekraft)" else ""

        fun pc(v: Double) = String.format(Locale.GERMANY, "%+.1f %%", v * 100)
        val lines = ArrayList<String>()
        lines.add(String.format(Locale.GERMANY, "Steigend %.0f %% · Fallend %.0f %% (Anteil ähnlicher Verläufe, die danach stiegen bzw. fielen)", pctUp, 100 - pctUp))
        lines.add(String.format(Locale.GERMANY, "Basis: %d ähnliche Verläufe aus %.0f Jahren Historie, Horizont %d Tage", a.analogs, a.years, a.horizonDays))
        lines.add(
            if (a.hitRate >= 0)
                String.format(Locale.GERMANY, "Trefferquote dieser Methode bei diesem Wert: %.0f %% (%d Rückwärts-Tests, Zufall wäre 50 %%)", a.hitRate * 100, a.tests)
            else "Trefferquote: zu wenig Historie für einen Rückwärts-Test"
        )
        lines.add(
            "Spanne am Ende: " + pc(a.upRel.last()) + " / " + pc(a.downRel.last()) + " (Median " + pc(a.medRel.last()) + ")" +
                String.format(Locale.GERMANY, " · Verlust über %.0f %%: %.0f %%", a.lossThr * 100, a.pLoss * 100)
        )
        return Outlook(up, down, label, lines.joinToString("\n"), 0, a.pUp, lines)
    }
}
