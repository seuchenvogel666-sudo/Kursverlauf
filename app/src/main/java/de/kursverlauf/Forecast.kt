package de.kursverlauf

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class Info(val id: String, val heading: String, val text: String)

/**
 * tendency: Einordnung (Positiv ... Negativ), recommendation: Abwarten / Kaufen / Verkaufen,
 * signal: 0 = klar Verkaufen ... 1 = klar Kaufen, info: Informationsabschnitte (Überschrift + Zeile).
 */
class Outlook(
    val up: List<Point>, val down: List<Point>,
    val tendency: String, val recommendation: String,
    val signal: Double, val signalNote: String,
    val info: List<Info>,
    val pUp: Double? = null,
    val mid: List<Point>? = null
)

object Forecast {
    private fun pc(v: Double) = String.format(Locale.GERMANY, "%+.1f %%", v * 100)

    private fun dur(sec: Long): String =
        if (sec >= 2 * 86400L) String.format(Locale.GERMANY, "%.0f Tage", sec / 86400.0)
        else String.format(Locale.GERMANY, "%.0f Stunden", sec / 3600.0)

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

    /** Wann wurde die Historie gelesen und welchen Zeitraum umfasst sie. */
    fun histText(meta: HistMeta, a: Analysis?): String {
        val df = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
        val dtf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
        val days = (meta.to - meta.from) / 86400.0
        val span = if (days >= 365) String.format(Locale.GERMANY, "%.1f Jahre", days / 365.25)
        else String.format(Locale.GERMANY, "%.0f Tage", days)
        val unit = if (meta.hourly) "Stundenkurse" else "Tageskurse"
        val sb = StringBuilder()
        sb.append("Gelesen am ").append(dtf.format(Date(meta.loadedAt)))
        sb.append(" · Zeitraum ").append(df.format(Date(meta.from * 1000))).append(" bis ").append(df.format(Date(meta.to * 1000)))
        sb.append(String.format(Locale.GERMANY, " (%s, %,d %s)", span, meta.bars, unit))
        if (meta.hourly) sb.append("\nDie Tageshistorie ist zu kurz, die Basis wurde aus Stundenkursen aufgebaut.")
        if (a != null && a.reduced)
            sb.append("\nKürzere Historie als üblich: Muster (").append(dur(a.patternBars * a.barSec))
                .append(") und Horizont (").append(dur(a.horizonSec)).append(") wurden angepasst.")
        return sb.toString()
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
            Info("basis", "Basis", "Einfache Schätzung aus dem sichtbaren Verlauf (Trend, RSI, Momentum). Die Musteranalyse der Historie fehlt noch."),
            Info("kenn", "Kennwerte", String.format(Locale.GERMANY, "Trend %s · RSI %.0f · Momentum %s", if (trend > 0) "aufwärts" else "abwärts", rsi, pc(mom)))
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
        val span = a.horizonSec

        fun at(arr: DoubleArray, i: Int): Double {
            val pos = i.toDouble() / h * (arr.size - 1)
            val lo = floor(pos).toInt().coerceIn(0, arr.size - 1)
            val hi = min(arr.size - 1, lo + 1)
            val f = pos - lo
            return arr[lo] * (1 - f) + arr[hi] * f
        }
        val up = (0..h).map { i -> Point(last.t + span * i / h, last.v * (1 + at(a.upRel, i))) }
        val down = (0..h).map { i -> Point(last.t + span * i / h, last.v * (1 + at(a.downRel, i))) }
        val mid = (0..h).map { i -> Point(last.t + span * i / h, last.v * (1 + at(a.medRel, i))) }

        // Empfehlung: Wahrscheinlichkeit wird bei schwacher Trefferquote Richtung 50 % gezogen
        val f = if (a.hitRate < 0) 0.5 else ((a.hitRate - 0.5) / 0.08).coerceIn(0.25, 1.0)
        val signal = 0.5 + (a.pUp - 0.5) * f
        val weakNote = if (a.hitRate < 0.53) " · abgeschwächt wegen geringer Trefferquote" else ""

        val pctUp = a.pUp * 100
        val years = (a.meta.to - a.meta.from) / 31_557_600.0
        val yearsTxt = if (years >= 1) String.format(Locale.GERMANY, "%.1f Jahren", years) else String.format(Locale.GERMANY, "%.0f Tagen", years * 365.25)
        val info = listOf(
            Info("updown", "Steigend / Fallend", String.format(Locale.GERMANY, "%.0f %% / %.0f %% der ähnlichen Verläufe stiegen bzw. fielen danach", pctUp, 100 - pctUp)),
            Info("basis", "Basis", String.format(Locale.GERMANY, "%d ähnliche Verläufe aus %s Historie, Muster der letzten %s, Horizont %s", a.analogs, yearsTxt, dur(a.patternBars * a.barSec), dur(a.horizonSec))),
            Info("hit", "Trefferquote",
                if (a.hitRate >= 0)
                    String.format(Locale.GERMANY, "%.0f %% bei diesem Wert (%d Rückwärts-Tests, Zufall wäre 50 %%)", a.hitRate * 100, a.tests)
                else "Zu wenig Historie für einen Rückwärts-Test"),
            Info("range", "Spanne am Ende", pc(a.upRel.last()) + " / " + pc(a.downRel.last()) + " (Median " + pc(a.medRel.last()) + ")"),
            Info("loss", "Verlustrisiko", String.format(Locale.GERMANY, "Verlust über %.0f %%: %.0f %%", a.lossThr * 100, a.pLoss * 100)),
            Info("history", "Historie", histText(a.meta, a))
        )
        return Outlook(up, down, tendencyOf(a.pUp), recommendationOf(signal), signal, noteOf(signal, weakNote), info, a.pUp, mid)
    }
}
