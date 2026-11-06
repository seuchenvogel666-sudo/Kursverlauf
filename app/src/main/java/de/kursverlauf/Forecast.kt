package de.kursverlauf

import java.util.Locale
import kotlin.math.*

/**
 * tendency: Einordnung (Positiv ... Negativ), recommendation: Abwarten / Kaufen / Verkaufen,
 * signal: 0 = klar Verkaufen ... 1 = klar Kaufen, info: Überschrift + Informationszeile.
 */
class Outlook(
    val up: List<Point>, val down: List<Point>,
    val tendency: String, val recommendation: String,
    val signal: Double, val signalNote: String,
    val info: List<Pair<String, String>>,
    val pUp: Double? = null,
    val mid: List<Point>? = null
)

object Forecast {
    private fun pc(v: Double) = String.format(Locale.GERMANY, "%+.1f %%", v * 100)

    private fun tendencyOf(p: Double) = when {
        p >= 0.65 -> "Positiv"
        p >= 0.55 -> "Eher positiv"
        p > 0.45 -> "Neutral"
        p > 0.35 -> "Eher negativ"
        else -> "Negativ"
    }

    private fun recommendationOf(signal: Double) = when {
        signal >= 0.60 -> "Kaufen"
        signal <= 0.40 -> "Verkaufen"
        else -> "Abwarten"
    }

    private fun noteOf(signal: Double, extra: String = ""): String {
        val strength = abs(signal - 0.5) * 2 * 100
        val dir = if (signal >= 0.5) "Richtung Kaufen" else "Richtung Verkaufen"
        return String.format(Locale.GERMANY, "Signalstärke %.0f %% (%s)%s", strength, dir, extra)
    }

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
        val signal = (score + 3) / 6.0
        val tendency = when {
            score >= 3 -> "Positiv"; score == 2 -> "Eher positiv"
            score <= -3 -> "Negativ"; score == -2 -> "Eher negativ"
            else -> "Neutral"
        }
        val info = listOf(
            "Basis" to "Einfache Schätzung aus dem sichtbaren Verlauf (Trend, RSI, Momentum). Die Musteranalyse der Historie fehlt noch.",
            "Kennwerte" to String.format(Locale.GERMANY, "Trend %s · RSI %.0f · Momentum %s", if (trend > 0) "aufwärts" else "abwärts", rsi, pc(mom))
        )
        val mid = (0..h).map { k -> Point(last.t + k * dt, last.v * exp(mu * k)) }
        return Outlook(up, down, tendency, recommendationOf(signal), signal, noteOf(signal), info, null, mid)
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

        // Empfehlung: Wahrscheinlichkeit wird bei schwacher Trefferquote Richtung 50 % gezogen
        val f = if (a.hitRate < 0) 0.5 else ((a.hitRate - 0.5) / 0.08).coerceIn(0.25, 1.0)
        val signal = 0.5 + (a.pUp - 0.5) * f
        val weakNote = if (a.hitRate < 0.53) " · abgeschwächt wegen geringer Trefferquote" else ""

        val pctUp = a.pUp * 100
        val info = listOf(
            "Steigend / Fallend" to String.format(Locale.GERMANY, "%.0f %% / %.0f %% der ähnlichen Verläufe stiegen bzw. fielen danach", pctUp, 100 - pctUp),
            "Basis" to String.format(Locale.GERMANY, "%d ähnliche Verläufe aus %.0f Jahren Historie, Horizont %d Tage", a.analogs, a.years, a.horizonDays),
            "Trefferquote" to (
                if (a.hitRate >= 0)
                    String.format(Locale.GERMANY, "%.0f %% bei diesem Wert (%d Rückwärts-Tests, Zufall wäre 50 %%)", a.hitRate * 100, a.tests)
                else "Zu wenig Historie für einen Rückwärts-Test"),
            "Spanne am Ende" to (pc(a.upRel.last()) + " / " + pc(a.downRel.last()) + " (Median " + pc(a.medRel.last()) + ")"),
            "Verlustrisiko" to String.format(Locale.GERMANY, "Verlust über %.0f %%: %.0f %%", a.lossThr * 100, a.pLoss * 100)
        )
        val mid = (0..h).map { i -> Point(last.t + span * i / h, last.v * (1 + at(a.medRel, i))) }
        return Outlook(up, down, tendencyOf(a.pUp), recommendationOf(signal), signal, noteOf(signal, weakNote), info, a.pUp, mid)
    }
}
