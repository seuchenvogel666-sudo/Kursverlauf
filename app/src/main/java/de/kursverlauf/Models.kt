package de.kursverlauf

data class Point(val t: Long, val v: Double)          // t = Unix-Sekunden
data class Series(val points: List<Point>, val currency: String)
data class Quote(val symbol: String, val name: String, val type: String)
data class Watch(val symbol: String, val name: String)

enum class Range(val short: String, val label: String, val range: String, val interval: String) {
    D1("1T", "1 Tag", "1d", "1m"),
    W1("1W", "1 Woche", "5d", "15m"),
    M1("1M", "1 Monat", "1mo", "60m"),
    Y1("1J", "1 Jahr", "1y", "1d"),
    MAX("Max", "Maximal", "max", "1wk")
}

/** Festes Design "Nachtblau" */
object Pal {
    val BG = 0xFF0B1220.toInt()
    val PRICE = 0xFF4FC3F7.toInt()   // Verlauf (blau)
    val UP = 0xFF00E676.toInt()      // positives Szenario (grün)
    val DOWN = 0xFFFF5252.toInt()    // negatives Szenario (rot)
    val MID = 0xFFFFD54F.toInt()     // erwarteter Verlauf (gelb, gestrichelt)
}
