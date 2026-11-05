package de.kursverlauf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent { App(store) }
    }
}

enum class Screen { HOME, SEARCH, SETTINGS }

class PickerReq(val title: String, val initial: Int, val onPick: (Int) -> Unit)

@Composable
fun App(store: Store) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf(Screen.HOME) }
    var watch by remember { mutableStateOf(store.watch) }
    var appBg by remember { mutableStateOf(store.appBg) }
    var appTr by remember { mutableStateOf(store.appTransp) }
    var wBg by remember { mutableStateOf(store.widgetBg) }
    var wTr by remember { mutableStateOf(store.widgetTransp) }
    var range by remember { mutableStateOf(store.range) }
    var picker by remember { mutableStateOf<PickerReq?>(null) }

    val fg = textColorFor(appBg)
    val dark = fg == WHITE
    fun saveWatch(l: List<Watch>) { watch = l; store.watch = l }
    fun closeSettings() { ChartWidget.refreshAll(ctx); screen = Screen.HOME }

    BackHandler(screen != Screen.HOME) { if (screen == Screen.SETTINGS) closeSettings() else screen = Screen.HOME }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        CompositionLocalProvider(LocalContentColor provides Color(fg)) {
            Box(Modifier.fillMaxSize().background(Color(withTransparency(appBg, appTr))).systemBarsPadding().padding(horizontal = 12.dp)) {
                when (screen) {
                    Screen.HOME -> Home(
                        watch, range, fg,
                        onRange = { range = it; store.range = it },
                        onSearch = { screen = Screen.SEARCH },
                        onSettings = { screen = Screen.SETTINGS },
                        onColors = { w ->
                            picker = PickerReq("Farbe Verlauf: ${w.name}", w.price) { c ->
                                saveWatch(watch.map { if (it.symbol == w.symbol) it.copy(price = c) else it })
                            }
                        },
                        onRemove = { w -> saveWatch(watch.filter { it.symbol != w.symbol }) }
                    )
                    Screen.SEARCH -> Search(fg, onBack = { screen = Screen.HOME }) { q ->
                        if (watch.none { it.symbol == q.symbol }) saveWatch(watch + store.newWatch(q))
                        screen = Screen.HOME
                    }
                    Screen.SETTINGS -> Settings(
                        watch, appBg, appTr, wBg, wTr, store.themeIdx,
                        onTheme = { i ->
                            val t = Themes.all[i]
                            store.themeIdx = i
                            appBg = t.bg; store.appBg = t.bg
                            wBg = t.bg; store.widgetBg = t.bg
                            saveWatch(watch.map { it.copy(price = t.price, up = t.up, down = t.down) })
                        },
                        onAppTr = { appTr = it; store.appTransp = it },
                        onWTr = { wTr = it; store.widgetTransp = it },
                        pick = { picker = it },
                        onAppBg = { appBg = it; store.appBg = it },
                        onWBg = { wBg = it; store.widgetBg = it },
                        onWatch = { saveWatch(it) },
                        onBack = { closeSettings() }
                    )
                }
            }
            picker?.let { req ->
                ColorDialog(req.title, req.initial, onDismiss = { picker = null }) { req.onPick(it); picker = null }
            }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val base = LocalContentColor.current
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) base.copy(alpha = 0.28f) else base.copy(alpha = 0.08f))
            .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
}

@Composable
fun Home(
    watch: List<Watch>, range: Range, fg: Int,
    onRange: (Range) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit,
    onColors: (Watch) -> Unit, onRemove: (Watch) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Kursverlauf", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onSearch) { Text("＋ Suchen") }
            TextButton(onClick = onSettings) { Text("⚙ Einstellungen") }
        }
        Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Range.values().forEach { Chip(it.short, it == range) { onRange(it) } }
        }
        Spacer(Modifier.height(8.dp))
        if (watch.isEmpty()) Text("Noch keine Graphen. Oben auf „Suchen“ tippen.", Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(watch, key = { it.symbol }) { w -> ChartCard(w, range, fg, { onColors(w) }, { onRemove(w) }) }
        }
    }
}

// einfache horizontale Scroll-Zeile ohne Zusatzabhängigkeit
@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
fun ChartCard(w: Watch, range: Range, fg: Int, onColors: () -> Unit, onRemove: () -> Unit) {
    var series by remember(w.symbol, range) { mutableStateOf<Series?>(null) }
    var err by remember(w.symbol, range) { mutableStateOf<String?>(null) }
    var stamp by remember(w.symbol, range) { mutableStateOf(0L) }
    LaunchedEffect(w.symbol, range) {
        while (true) {
            try {
                series = withContext(Dispatchers.IO) { Api.chart(w.symbol, range) }
                err = null; stamp = System.currentTimeMillis()
            } catch (e: Exception) { err = "Keine Verbindung oder keine Daten für dieses Symbol" }
            delay(60_000)
        }
    }
    val pts = series?.points ?: emptyList()
    val outlook = remember(series) { Forecast.compute(pts) }
    val col = ChartColors(w.price, w.up, w.down, fg, 0)
    val chg = if (pts.size >= 2) pts.last().v / pts.first().v - 1 else 0.0

    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(Color(fg).copy(alpha = 0.08f)).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(w.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text(w.symbol, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            if (pts.size >= 2) Column(horizontalAlignment = Alignment.End) {
                Text(ChartRenderer.fmt(pts.last().v) + " " + (series?.currency ?: ""), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(String.format(Locale.GERMANY, "%+.2f %%", chg * 100), color = Color(if (chg >= 0) w.up else w.down), fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (series == null) {
            Text(err ?: "Lade Daten …", Modifier.padding(vertical = 40.dp))
        } else {
            Canvas(Modifier.fillMaxWidth().height(240.dp)) {
                drawIntoCanvas { ChartRenderer.draw(it.nativeCanvas, size.width, size.height, density, pts, outlook, col, range, series?.currency ?: "") }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("Verlauf", w.price); Legend("Positiv-Szenario", w.up); Legend("Negativ-Szenario", w.down)
        }
        if (outlook != null) {
            Text(outlook.label, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(outlook.detail, fontSize = 12.sp)
        }
        Text(
            "Statistische Szenarien aus vergangenen Kursen. Keine Kaufempfehlung und keine Anlageberatung.",
            fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (stamp > 0) "Stand " + SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(stamp)) + (err?.let { " · $it" } ?: "") else "",
                fontSize = 11.sp, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onColors) { Text("Farbe") }
            TextButton(onClick = onRemove) { Text("Entfernen") }
        }
    }
}

@Composable
fun Legend(text: String, color: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(color)))
        Spacer(Modifier.width(4.dp)); Text(text, fontSize = 11.sp)
    }
}

@Composable
fun Search(fg: Int, onBack: () -> Unit, onAdd: (Quote) -> Unit) {
    var q by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<List<Quote>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.trim().length < 2) { res = emptyList(); return@LaunchedEffect }
        delay(400)
        loading = true
        res = withContext(Dispatchers.IO) { Api.search(q.trim()) }
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            TextButton(onClick = onBack) { Text("← Zurück") }
            Text("Suchen & hinzufügen", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        OutlinedTextField(
            value = q, onValueChange = { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Ticker, ISIN, Name oder z. B. „Krypto“") }
        )
        Text(
            if (loading) "Suche …" else if (q.length >= 2 && res.isEmpty()) "Keine Treffer" else "Tippen zum Hinzufügen. WKN-Suche klappt nicht immer, dann ISIN oder Ticker nutzen.",
            fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)
        )
        LazyColumn {
            items(res, key = { it.symbol }) { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { onAdd(r) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(r.symbol + " · " + r.type, fontSize = 12.sp)
                    }
                    Text("＋", fontSize = 22.sp)
                }
                HorizontalDivider(color = Color(fg).copy(alpha = 0.12f))
            }
        }
    }
}

@Composable
fun Swatch(color: Int, label: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(color or (0xFF shl 24))))
        Spacer(Modifier.width(6.dp)); Text(label, fontSize = 13.sp)
    }
}

@Composable
fun Settings(
    watch: List<Watch>, appBg: Int, appTr: Int, wBg: Int, wTr: Int, themeIdx: Int,
    onTheme: (Int) -> Unit, onAppTr: (Int) -> Unit, onWTr: (Int) -> Unit,
    pick: (PickerReq) -> Unit, onAppBg: (Int) -> Unit, onWBg: (Int) -> Unit,
    onWatch: (List<Watch>) -> Unit, onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            TextButton(onClick = onBack) { Text("← Zurück") }
            Text("Einstellungen", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text("Design-Vorlagen (setzt Farben aller Graphen und Hintergründe)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Themes.all.forEachIndexed { i, t ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(if (i == themeIdx) LocalContentColor.current.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable { onTheme(i) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Color(t.bg)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(Color(t.price)))
                }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(t.up)))
                Spacer(Modifier.width(3.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(t.down)))
                Spacer(Modifier.width(10.dp))
                Column { Text("${i + 1}. ${t.name}", fontWeight = FontWeight.Bold); Text(t.desc, fontSize = 12.sp) }
            }
        }

        Text("App-Hintergrund", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Swatch(appBg, "Farbe wählen") { pick(PickerReq("App-Hintergrund", appBg, onAppBg)) }
        Text("Durchsichtigkeit: $appTr %  (0 = deckend, 100 = durchsichtig)", fontSize = 13.sp)
        Slider(appTr.toFloat(), { onAppTr(it.toInt()) }, valueRange = 0f..100f)

        Text("Widget-Hintergrund", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Swatch(wBg, "Farbe wählen") { pick(PickerReq("Widget-Hintergrund", wBg, onWBg)) }
        Text("Durchsichtigkeit: $wTr %  (0 = deckend, 100 = durchsichtig)", fontSize = 13.sp)
        Slider(wTr.toFloat(), { onWTr(it.toInt()) }, valueRange = 0f..100f)

        Text("Farben je Graph", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        watch.forEach { w ->
            Text(w.name + "  (" + w.symbol + ")", modifier = Modifier.padding(top = 8.dp), maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Swatch(w.price, "Verlauf") { pick(PickerReq("Verlauf: ${w.name}", w.price) { c -> onWatch(watch.map { if (it.symbol == w.symbol) it.copy(price = c) else it }) }) }
                Swatch(w.up, "Positiv") { pick(PickerReq("Positiv: ${w.name}", w.up) { c -> onWatch(watch.map { if (it.symbol == w.symbol) it.copy(up = c) else it }) }) }
                Swatch(w.down, "Negativ") { pick(PickerReq("Negativ: ${w.name}", w.down) { c -> onWatch(watch.map { if (it.symbol == w.symbol) it.copy(down = c) else it }) }) }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

private val palette = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFF5252, 0xFFFF9800, 0xFFFFEB3B, 0xFF00E676, 0xFF26A69A, 0xFF4FC3F7,
    0xFF1565C0, 0xFF7C4DFF, 0xFFB388FF, 0xFFFF1493, 0xFF6D4C41, 0xFF607D8B, 0xFF111111, 0xFF0B1220
).map { it.toInt() }

@Composable
fun ColorDialog(title: String, initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    var r by remember { mutableStateOf(((initial shr 16) and 0xFF).toFloat()) }
    var g by remember { mutableStateOf(((initial shr 8) and 0xFF).toFloat()) }
    var b by remember { mutableStateOf((initial and 0xFF).toFloat()) }
    val cur = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(cur)))
                Spacer(Modifier.height(8.dp))
                palette.chunked(8).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                        row.forEach { c ->
                            Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).clickable {
                                r = ((c shr 16) and 0xFF).toFloat(); g = ((c shr 8) and 0xFF).toFloat(); b = (c and 0xFF).toFloat()
                            })
                        }
                    }
                }
                Text("Rot", fontSize = 12.sp); Slider(r, { r = it }, valueRange = 0f..255f)
                Text("Grün", fontSize = 12.sp); Slider(g, { g = it }, valueRange = 0f..255f)
                Text("Blau", fontSize = 12.sp); Slider(b, { b = it }, valueRange = 0f..255f)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(cur) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
