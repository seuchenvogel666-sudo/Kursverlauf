package de.kursverlauf

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class LiveQuote(val price: Double, val t: Long)

object Api {
    const val SOURCE = "Yahoo Finance (finance.yahoo.com)"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) Kursverlauf/1.0"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Kursverlauf für den Anzeigezeitraum: großzügig laden, dann auf das Fenster ab dem letzten Kurs zuschneiden. */
    fun chart(symbol: String, r: Range): Series {
        val now = System.currentTimeMillis() / 1000
        val from = if (r.fetchDays == 0) 0L else now - r.fetchDays * 86400L
        val s = chartPeriod(symbol, from, now, r.interval)
        if (r.windowDays == 0 || s.points.isEmpty()) return s
        val cut = s.points.last().t - r.windowDays * 86400L
        return Series(s.points.filter { it.t >= cut }, s.currency)
    }

    fun chartPeriod(symbol: String, fromSec: Long, toSec: Long, interval: String): Series =
        parse(get("https://query1.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?period1=$fromSec&period2=$toSec&interval=$interval"))

    /** Aktueller Kurs (klein und schnell, für die 10-Sekunden-Abfrage). */
    fun quote(symbol: String): LiveQuote {
        val j = JSONObject(get("https://query1.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?range=1d&interval=1d"))
        val meta = j.getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta")
        return LiveQuote(meta.getDouble("regularMarketPrice"), meta.optLong("regularMarketTime", System.currentTimeMillis() / 1000))
    }

    private fun parse(text: String): Series {
        val j = JSONObject(text)
        val res = j.getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val cur = res.optJSONObject("meta")?.optString("currency", "") ?: ""
        val ts = res.optJSONArray("timestamp") ?: return Series(emptyList(), cur)
        val close = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
        val out = ArrayList<Point>(ts.length())
        for (i in 0 until ts.length()) {
            if (!close.isNull(i)) out.add(Point(ts.getLong(i), close.getDouble(i)))
        }
        return Series(out, cur)
    }

    // kleiner eingebauter Katalog, damit Begriffe wie "Krypto" oder "ETF" immer Treffer liefern
    private val catalog = listOf(
        Quote("BTC-USD", "Bitcoin", "Krypto"), Quote("ETH-USD", "Ethereum", "Krypto"),
        Quote("SOL-USD", "Solana", "Krypto"), Quote("XRP-USD", "XRP", "Krypto"),
        Quote("BNB-USD", "BNB", "Krypto"), Quote("ADA-USD", "Cardano", "Krypto"),
        Quote("DOGE-USD", "Dogecoin", "Krypto"), Quote("LTC-USD", "Litecoin", "Krypto"),
        Quote("DOT-USD", "Polkadot", "Krypto"), Quote("AVAX-USD", "Avalanche", "Krypto"),
        Quote("EUNL.DE", "iShares Core MSCI World UCITS ETF", "ETF"),
        Quote("VWCE.DE", "Vanguard FTSE All-World UCITS ETF", "ETF"),
        Quote("SXR8.DE", "iShares Core S&P 500 UCITS ETF", "ETF"),
        Quote("EXS1.DE", "iShares Core DAX UCITS ETF", "ETF"),
        Quote("SPY", "SPDR S&P 500 ETF", "ETF"), Quote("QQQ", "Invesco QQQ (Nasdaq 100) ETF", "ETF"),
        Quote("^GDAXI", "DAX", "Index"), Quote("^GSPC", "S&P 500", "Index")
    )
    private val synonyms = mapOf("krypto" to "krypto", "crypto" to "krypto", "coin" to "krypto", "kryptowährung" to "krypto")

    fun localSearch(q: String): List<Quote> {
        val tokens = q.lowercase().split(" ").filter { it.isNotBlank() }.map { synonyms[it] ?: it }
        if (tokens.isEmpty()) return emptyList()
        return catalog.filter { c ->
            val hay = (c.symbol + " " + c.name + " " + c.type).lowercase()
            tokens.all { hay.contains(it) }
        }
    }

    /** Volltextsuche: Name, Ticker, ISIN, Begriffe wie "Krypto". */
    fun search(q: String): List<Quote> {
        val local = localSearch(q)
        val remote = try {
            val arr = JSONObject(get("https://query2.finance.yahoo.com/v1/finance/search?q=${enc(q)}&quotesCount=20&newsCount=0&lang=de-DE"))
                .optJSONArray("quotes")
            val l = ArrayList<Quote>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sym = o.optString("symbol")
                if (sym.isEmpty()) continue
                val name = o.optString("shortname", o.optString("longname", sym))
                val type = when (val t = o.optString("quoteType")) {
                    "EQUITY" -> "Aktie"; "ETF" -> "ETF"; "CRYPTOCURRENCY" -> "Krypto"
                    "MUTUALFUND" -> "Fonds"; "INDEX" -> "Index"; else -> t
                }
                val ex = o.optString("exchDisp")
                l.add(Quote(sym, name, if (ex.isNotEmpty()) "$type · $ex" else type))
            }
            l
        } catch (e: Exception) { emptyList() }
        return (local + remote).distinctBy { it.symbol }.take(30)
    }
}
