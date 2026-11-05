package de.kursverlauf

data class Point(val t: Long, val v: Double)          // t = Unix-Sekunden
data class Series(val points: List<Point>, val currency: String)
data class Quote(val symbol: String, val name: String, val type: String)
data class Watch(val symbol: String, val name: String, val price: Int, val up: Int, val down: Int)

enum class Range(val short: String, val label: String, val range: String, val interval: String) {
    D1("1T", "1 Tag", "1d", "1m"),
    W1("1W", "1 Woche", "5d", "15m"),
    M1("1M", "1 Monat", "1mo", "60m"),
    Y1("1J", "1 Jahr", "1y", "1d"),
    MAX("Max", "Maximal", "max", "1wk")
}

class Theme(val name: String, val desc: String, val bg: Int, val price: Int, val up: Int, val down: Int)

object Themes {
    private fun c(h: Long) = h.toInt()
    val all = listOf(
        Theme("Nachtblau", "Dunkles Marineblau, Cyan-Linie", c(0xFF0B1220), c(0xFF4FC3F7), c(0xFF00E676), c(0xFFFF5252)),
        Theme("AMOLED Schwarz", "Reines Schwarz, weiße Linie, spart Akku", c(0xFF000000), c(0xFFFFFFFF), c(0xFF69F0AE), c(0xFFFF6E6E)),
        Theme("Hell Klassisch", "Weiß, Blau-Linie, wie klassische Börsen-Apps", c(0xFFFFFFFF), c(0xFF1565C0), c(0xFF2E7D32), c(0xFFC62828)),
        Theme("Terminal Orange", "Anthrazit mit Orange, Profi-Terminal-Look", c(0xFF111111), c(0xFFFFA726), c(0xFF66BB6A), c(0xFFEF5350)),
        Theme("Mint Frisch", "Helles Mint, ruhig und freundlich", c(0xFFE8F5F1), c(0xFF00796B), c(0xFF43A047), c(0xFFE53935)),
        Theme("Cyberpunk", "Tiefviolett mit Neon-Cyan, Neon-Grün, Neon-Pink", c(0xFF120024), c(0xFF00E5FF), c(0xFF39FF14), c(0xFFFF1493)),
        Theme("Sand & Kaffee", "Warmes Creme, Braun-Linie, Retro-Papier", c(0xFFFFF8E1), c(0xFF6D4C41), c(0xFF7CB342), c(0xFFD84315)),
        Theme("Wald Dunkel", "Dunkelgrün, Salbei-Linie, natürlich", c(0xFF0F1F17), c(0xFFA5D6A7), c(0xFF76FF03), c(0xFFFF7043)),
        Theme("Rosé Pastell", "Zartes Rosa, Beere-Linie, weich", c(0xFFFFF0F5), c(0xFFAD1457), c(0xFF26A69A), c(0xFFD32F2F)),
        Theme("Violett Dämmerung", "Dunkles Indigo, Flieder-Linie, elegant", c(0xFF1A1033), c(0xFFB388FF), c(0xFF69F0AE), c(0xFFFF8A80))
    )
}
