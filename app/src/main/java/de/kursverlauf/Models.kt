package de.kursverlauf

data class Point(val t: Long, val v: Double)          // t = Unix-Sekunden
data class Series(val points: List<Point>, val currency: String)
data class Quote(val symbol: String, val name: String, val type: String)
data class Watch(val symbol: String, val name: String)

/** Anzeigezeitraum. fetchDays = angefragte Tage, windowDays = sichtbares Fenster rückwärts ab dem letzten Kurs (0 = alles). */
enum class Range(val short: String, val label: String, val interval: String, val fetchDays: Int, val windowDays: Int) {
    D1("1T", "1 Tag", "2m", 3, 1),
    W1("1W", "1 Woche", "15m", 10, 7),
    M1("1M", "1 Monat", "60m", 35, 30),
    M6("6M", "6 Monate", "1d", 190, 183),
    Y1("1J", "1 Jahr", "1d", 370, 365),
    MAX("Max", "Maximal", "1wk", 0, 0)
}

/** Zeitraum, aus dem die Musteranalyse ihre Historie nimmt. */
enum class AnaPeriod(val label: String, val days: Int, val interval: String, val barSec: Long, val unit: String) {
    MAX("Maximal", 0, "1d", 86400L, "Tageskurse"),
    Y10("10 Jahre", 3653, "1d", 86400L, "Tageskurse"),
    Y5("5 Jahre", 1826, "1d", 86400L, "Tageskurse"),
    Y3("3 Jahre", 1096, "1d", 86400L, "Tageskurse"),
    Y1("1 Jahr", 365, "1d", 86400L, "Tageskurse"),
    M6("6 Monate", 183, "60m", 3600L, "Stundenkurse"),
    M1("1 Monat", 31, "30m", 1800L, "30-Minuten-Kurse"),
    D1("1 Tag", 1, "1m", 60L, "Minutenkurse")
}

/** Welche Hilfsgraphen im Diagramm gezeichnet werden. */
data class ChartOpts(val mid: Boolean = true, val up: Boolean = true, val down: Boolean = true, val levels: Boolean = true, val baseline: Boolean = true, val legend: Boolean = true)

/** Festes Design "Nachtblau" */
object Pal {
    val BG = 0xFF0B1220.toInt()
    val PRICE = 0xFF4FC3F7.toInt()   // Verlauf (blau)
    val UP = 0xFF00E676.toInt()      // positives Szenario (grün)
    val DOWN = 0xFFFF5252.toInt()    // negatives Szenario (rot)
    val MID = 0xFFFFD54F.toInt()     // erwarteter Verlauf (gelb, gestrichelt)
}
