package de.kursverlauf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

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
    var prefsN by remember { mutableStateOf(0) }
    var restartN by remember { mutableStateOf(0) }
    var resetN by remember { mutableStateOf(0) }
    val opts = remember(prefsN) { store.opts }
    val anaPeriod = remember(prefsN) { store.analysisPeriod }

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
                        watch, range, opts, anaPeriod, restartN, resetN,
                        onRange = { range = it; store.range = it; resetN++ },
                        onSearch = { screen = Screen.SEARCH },
                        onSettings = { screen = Screen.SETTINGS },
                        onRemove = { w -> saveWatch(watch.filter { it.symbol != w.symbol }) },
                        onMove = { a, b -> val l = watch.toMutableList(); l.add(b, l.removeAt(a)); saveWatch(l) }
                    )
                    Screen.SEARCH -> Search(fg, watch, onBack = { screen = Screen.HOME }) { q ->
                        if (watch.none { it.symbol == q.symbol }) saveWatch(watch + Watch(q.symbol, q.name))
                    }
                    Screen.SETTINGS -> Settings(
                        appBg, appTr, wBg, wTr, store,
                        onAppTr = { appTr = it; store.appTransp = it },
                        onWTr = { wTr = it; store.widgetTransp = it },
                        pick = { picker = it },
                        onAppBg = { appBg = it; store.appBg = it },
                        onWBg = { wBg = it; store.widgetBg = it },
                        onReset = {
                            appBg = Pal.BG; store.appBg = Pal.BG; appTr = 0; store.appTransp = 0
                            wBg = Pal.BG; store.widgetBg = Pal.BG; wTr = 15; store.widgetTransp = 15
                        },
                        onPrefs = { prefsN++; ChartWidget.schedule(ctx) },
                        onRestartAnalysis = { Analog.clearAll(); restartN++; ChartWidget.refreshAll(ctx) },
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

/** Gemeinsame Steuerung fürs Ziehen: Auto-Scroll am Bildschirmrand. */
class DragCtl {
    var active by mutableStateOf(false)
    var pointerY = 0f
    var viewTop = 0f
    var viewBottom = 0f
    var slow = false
    var lastEnd = 0L
    var onScrolled: (Float) -> Unit = {}
}

/** Zwei-Finger-Geste: Zoomen und Verschieben. Ein Finger bleibt für Scrollen, Tippen und langes Drücken frei. */
suspend fun PointerInputScope.twoFingerTransform(onTransform: (Float, Float, Float) -> Unit, onEnd: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var used = false
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = true)
                if (zoom != 1f || pan.x != 0f) {
                    onTransform(centroid.x / size.width, zoom, pan.x / size.width)
                    used = true
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (used) onEnd()
    }
}

@Composable
fun Home(
    watch: List<Watch>, range: Range, opts: ChartOpts, anaPeriod: AnaPeriod, restartN: Int, resetN: Int,
    onRange: (Range) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit,
    onRemove: (Watch) -> Unit, onMove: (Int, Int) -> Unit
) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    var secOrder by remember { mutableStateOf(store.sectionOrder) }
    val scroll = rememberScrollState()
    val ctl = remember { DragCtl() }
    val curWatch by rememberUpdatedState(watch)
    val curMove by rememberUpdatedState(onMove)
    val heights = remember { HashMap<String, Int>() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val density = LocalDensity.current.density

    fun maybeSwap() {
        val dk = dragKey ?: return
        val l = curWatch
        val i = l.indexOfFirst { it.symbol == dk }
        if (i < 0) return
        val hs = heights[dk] ?: return
        if (dragOffset > 0 && i < l.lastIndex) {
            val hn = heights[l[i + 1].symbol] ?: return
            if (dragOffset > (hs + hn) / 2f) { curMove(i, i + 1); dragOffset -= hn }
        } else if (dragOffset < 0 && i > 0) {
            val hp = heights[l[i - 1].symbol] ?: return
            if (dragOffset < -(hs + hp) / 2f) { curMove(i, i - 1); dragOffset += hp }
        }
    }

    // Auto-Scroll: Graphen ziehen = 1x bis 3x schneller je näher am Rand, Abschnitte ziehen = langsam
    LaunchedEffect(ctl.active) {
        if (!ctl.active) return@LaunchedEffect
        while (true) {
            val zone = (if (ctl.slow) 60f else 90f) * density
            val dTop = ctl.pointerY - ctl.viewTop
            val dBot = ctl.viewBottom - ctl.pointerY
            var d = 0f
            if (dTop < zone) {
                val depth = ((zone - dTop) / zone).coerceIn(0f, 1f)
                d = -(if (ctl.slow) 4f else 10f * (1f + 2f * depth))
            } else if (dBot < zone) {
                val depth = ((zone - dBot) / zone).coerceIn(0f, 1f)
                d = if (ctl.slow) 4f else 10f * (1f + 2f * depth)
            }
            if (d != 0f) { val used = scroll.scrollBy(d); ctl.onScrolled(used) }
            delay(16)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Kursverlauf", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onSearch) { Text("＋ Suchen") }
            TextButton(onClick = onSettings) { Text("⚙ Einstellungen") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Range.values().forEach { Chip(it.short, it == range) { onRange(it) } }
        }
        Spacer(Modifier.height(8.dp))
        if (watch.isEmpty()) Text("Noch keine Graphen. Oben auf „Suchen“ tippen.", Modifier.padding(16.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onGloballyPositioned { val b = it.boundsInWindow(); ctl.viewTop = b.top; ctl.viewBottom = b.bottom }
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll, enabled = !ctl.active)) {
                watch.forEach { w ->
                    key(w.symbol) {
                        val dragging = dragKey == w.symbol
                        Box(
                            Modifier.zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    if (dragging) { translationY = dragOffset; scaleX = 1.02f; scaleY = 1.02f; alpha = 0.93f }
                                }
                                .onSizeChanged { heights[w.symbol] = it.height }
                        ) {
                            ChartCard(
                                w, range, opts, anaPeriod, restartN, resetN, secOrder,
                                onReorder = { disp ->
                                    val full = (secOrder + disp.filter { it !in secOrder }).toMutableList()
                                    val slots = full.indices.filter { full[it] in disp }
                                    slots.forEachIndexed { k, pos -> full[pos] = disp[k] }
                                    secOrder = full; store.sectionOrder = full
                                },
                                ctl = ctl,
                                onCardDragStart = { y ->
                                    dragKey = w.symbol; dragOffset = 0f
                                    ctl.slow = false; ctl.pointerY = y
                                    ctl.onScrolled = { used -> dragOffset += used; maybeSwap() }
                                    ctl.active = true
                                },
                                onCardDrag = { dy -> dragOffset += dy; ctl.pointerY += dy; maybeSwap() },
                                onCardDragEnd = { dragKey = null; dragOffset = 0f; ctl.active = false; ctl.lastEnd = System.currentTimeMillis() },
                                onRemove = { onRemove(w) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Ein Informationsabschnitt in der Karte; die Überschrift dient zum Verschieben. */
class SecDef(val id: String, val heading: String, val body: @Composable () -> Unit)

/** Name von Rot (Verkaufen) über Gelb zu Grün (Kaufen). */
fun signalColor(s: Double): Color {
    val x = (0.5f + (s.toFloat() - 0.5f) * 2.5f).coerceIn(0f, 1f)
    val red = Color(Pal.DOWN); val yellow = Color(Pal.MID); val green = Color(Pal.UP)
    return if (x < 0.5f) lerp(red, yellow, x * 2f) else lerp(yellow, green, (x - 0.5f) * 2f)
}

/** Fortschritt mit Restzeit. Eigene Komposition, damit nur dieser Teil neu gezeichnet wird. */
@Composable
fun AnalysisProgress(progress: () -> Int, startMs: () -> Long) {
    val p = progress()
    val elapsed = (System.currentTimeMillis() - startMs()).coerceAtLeast(0L) / 1000.0
    val eta = if (p in 3..99) elapsed * (100 - p) / p else -1.0
    val etaTxt = when {
        eta < 0 -> "Restzeit wird geschätzt …"
        eta < 90 -> String.format(Locale.GERMANY, "noch ca. %.0f s", eta)
        else -> String.format(Locale.GERMANY, "noch ca. %.0f Min", eta / 60)
    }
    Column(Modifier.padding(top = 6.dp)) {
        Text("Musteranalyse läuft: $p % · $etaTxt", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        LinearProgressIndicator(
            progress = { p / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            color = Color(Pal.PRICE), trackColor = Color(WHITE).copy(alpha = 0.15f)
        )
    }
}

/** Zwischenspeicher: beim Zurückkehren erscheinen die Daten sofort, nichts springt. */
object Live {
    val series = HashMap<String, Series>()
    val quotes = HashMap<String, LiveQuote>()
}

/** Den aktuellen Kurs als letzten Punkt in den Verlauf einsetzen. */
fun applyLive(base: List<Point>, q: LiveQuote?): List<Point> {
    if (q == null || base.isEmpty()) return base
    val last = base.last()
    return when {
        q.t > last.t -> base + Point(q.t, q.price)
        q.t == last.t -> base.dropLast(1) + Point(last.t, q.price)
        else -> base
    }
}

@Composable
fun ChartCard(
    w: Watch, range: Range, opts: ChartOpts, anaPeriod: AnaPeriod, restartN: Int, resetN: Int,
    order: List<String>, onReorder: (List<String>) -> Unit, ctl: DragCtl,
    onCardDragStart: (Float) -> Unit, onCardDrag: (Float) -> Unit, onCardDragEnd: () -> Unit,
    onRemove: () -> Unit
) {
    val ctx = LocalContext.current
    val store = remember { Store(ctx) }
    val density = LocalDensity.current.density
    val ck = w.symbol + "|" + range.name
    val ak = ck + "|" + anaPeriod.name + "|" + restartN
    var collapsed by remember(w.symbol) { mutableStateOf(store.isCollapsed(w.symbol)) }
    var series by remember(ck) { mutableStateOf(Live.series[ck]) }
    var err by remember(ck) { mutableStateOf<String?>(null) }
    var stamp by remember(ck) { mutableStateOf(0L) }
    var live by remember(w.symbol) { mutableStateOf(Live.quotes[w.symbol]) }
    var analysis by remember(ak) { mutableStateOf(Analog.cached(w.symbol, range, anaPeriod)) }
    var meta by remember(ak) { mutableStateOf(Analog.histMeta(w.symbol, anaPeriod)) }
    var progress by remember(ak) { mutableStateOf(0) }
    var running by remember(ak) { mutableStateOf(false) }
    var startMs by remember(ak) { mutableStateOf(0L) }
    var tried by remember(ak) { mutableStateOf(Analog.cached(w.symbol, range, anaPeriod) != null) }
    var failReason by remember(ak) { mutableStateOf<String?>(null) }
    var viewFrom by remember(ck, resetN) { mutableStateOf(0f) }
    var viewTo by remember(ck, resetN) { mutableStateOf(1f) }

    // Aktueller Kurs alle 10 Sekunden (bei Fehlern mit Pause, damit der Dienst nicht überlastet wird)
    LaunchedEffect(w.symbol) {
        var failures = 0
        while (true) {
            try {
                val q = withContext(Dispatchers.IO) { Api.quote(w.symbol) }
                Live.quotes[w.symbol] = q; live = q; failures = 0
                delay(10_000)
            } catch (e: Exception) {
                failures++
                delay(min(60_000L, 10_000L * (1L shl min(failures, 3))))
            }
        }
    }
    // Verlauf für den gewählten Zeitraum jede Minute
    LaunchedEffect(ck) {
        while (true) {
            val t0 = System.currentTimeMillis()
            try {
                val s = withContext(Dispatchers.IO) { Api.chart(w.symbol, range) }
                Live.series[ck] = s
                series = s; err = null; stamp = System.currentTimeMillis()
            } catch (e: Exception) { err = "Keine Verbindung oder keine Daten für dieses Symbol" }
            delay((60_000 - (System.currentTimeMillis() - t0)).coerceAtLeast(1_000))
        }
    }
    // Musteranalyse getrennt davon (rechnet im Hintergrund, Ergebnis eine Stunde gültig)
    LaunchedEffect(ak) {
        while (true) {
            try {
                val a = withContext(Analog.dispatcher) {
                    Analog.ensure(w.symbol, range, anaPeriod) { p ->
                        if (p == 0) { startMs = System.currentTimeMillis(); running = true }
                        progress = p
                    }
                }
                if (a != null) analysis = a
            } catch (e: Exception) { }
            running = false
            meta = Analog.histMeta(w.symbol, anaPeriod)
            failReason = Analog.reason(w.symbol, range, anaPeriod)
            tried = true
            delay(60_000)
        }
    }

    val basePts = remember(series) { series?.points ?: emptyList() }
    val pts = remember(basePts, live) { applyLive(basePts, live) }
    val outlook = remember(pts, analysis) { analysis?.let { Forecast.fromAnalysis(pts, it) } ?: Forecast.compute(pts) }
    val pattern = remember(basePts) { Patterns.detect(basePts) }
    val levels = remember(pts) { LevelFinder.find(pts) }
    val col = ChartColors(Pal.PRICE, Pal.UP, Pal.DOWN, WHITE, 0)
    val chg = if (pts.size >= 2) pts.last().v / pts.first().v - 1 else 0.0
    val nameColor = if (collapsed && outlook != null) signalColor(outlook.signal) else Color(WHITE)

    val curStart by rememberUpdatedState(onCardDragStart)
    val curDrag by rememberUpdatedState(onCardDrag)
    val curEnd by rememberUpdatedState(onCardDragEnd)
    val curReorder by rememberUpdatedState(onReorder)
    var topWin by remember { mutableStateOf(0f) }

    // Abschnitte aufbauen und nach gespeicherter Reihenfolge sortieren
    val secs = ArrayList<SecDef>()
    if (pattern != null) secs.add(SecDef("pattern", "Aktuelles Chartmuster") {
        Text(pattern.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(Pal.MID))
        Text(pattern.meaning, fontSize = 12.sp)
    })
    if (outlook != null) {
        val tcol = when {
            outlook.tendency.contains("ositiv") -> Color(Pal.UP)
            outlook.tendency.contains("egativ") -> Color(Pal.DOWN)
            else -> Color(WHITE)
        }
        secs.add(SecDef("tendency", "Tendenz") {
            Text(outlook.tendency, color = tcol, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        })
        secs.add(SecDef("recommendation", "Empfehlung") {
            Text(outlook.recommendation, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(Pal.DOWN))) {
                Box(Modifier.fillMaxWidth(outlook.signal.toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(Color(Pal.UP)))
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Text("Verkaufen", fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("Kaufen", fontSize = 11.sp)
            }
            Text(outlook.signalNote, fontSize = 11.sp)
        })
        outlook.info.forEach { inf ->
            secs.add(SecDef(inf.id, inf.heading) {
                Text(inf.text, fontSize = 12.sp)
                if (inf.id == "basis" && running) AnalysisProgress({ progress }, { startMs })
            })
        }
    }
    val mt = meta
    val fr = failReason
    if (analysis == null && tried && !running)
        secs.add(SecDef("history", "Historie und Analyse") {
            if (fr != null) Text("Musteranalyse nicht möglich: $fr", fontSize = 12.sp)
            if (mt != null) Text(Forecast.histText(mt, null), fontSize = 12.sp)
        })
    val displayed = secs.sortedBy { val ix = order.indexOf(it.id); if (ix < 0) 999 else ix }
    val curIds by rememberUpdatedState(displayed.map { it.id })

    val secHeights = remember { HashMap<String, Int>() }
    val secWinTop = remember { HashMap<String, Float>() }
    var secDrag by remember { mutableStateOf<String?>(null) }
    var secOffset by remember { mutableStateOf(0f) }

    fun secSwap() {
        val dk = secDrag ?: return
        val ids = curIds
        val i = ids.indexOf(dk)
        if (i < 0) return
        val hs = secHeights[dk] ?: return
        if (secOffset > 0 && i < ids.lastIndex) {
            val hn = secHeights[ids[i + 1]] ?: return
            if (secOffset > 0.6f * (hs + hn)) { val l = ids.toMutableList(); l.add(i + 1, l.removeAt(i)); curReorder(l); secOffset -= hn }
        } else if (secOffset < 0 && i > 0) {
            val hp = secHeights[ids[i - 1]] ?: return
            if (secOffset < -0.6f * (hs + hp)) { val l = ids.toMutableList(); l.add(i - 1, l.removeAt(i)); curReorder(l); secOffset += hp }
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(LocalContentColor.current.copy(alpha = 0.08f)).padding(12.dp)
            .animateContentSize()
    ) {
        // Kopf + Graph: Tippen = ein-/ausklappen, langes Drücken und Ziehen = Graph verschieben
        Column(
            Modifier.fillMaxWidth()
                .onGloballyPositioned { topWin = it.positionInWindow().y }
                .pointerInput(w.symbol) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { off -> curStart(topWin + off.y) },
                        onDrag = { change, amount -> change.consume(); curDrag(amount.y) },
                        onDragEnd = { curEnd() },
                        onDragCancel = { curEnd() }
                    )
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (!ctl.active && System.currentTimeMillis() - ctl.lastEnd > 300) {
                        collapsed = !collapsed
                        store.setCollapsed(w.symbol, collapsed)
                    }
                }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, color = nameColor)
                    Text(w.symbol, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                if (pts.size >= 2) Column(horizontalAlignment = Alignment.End) {
                    Text(ChartRenderer.fmt(pts.last().v) + " " + (series?.currency ?: ""), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        String.format(Locale.GERMANY, "%+.2f %% (%s)", chg * 100, range.label),
                        color = Color(if (chg >= 0) Pal.UP else Pal.DOWN), fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (series == null) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { Text(err ?: "Lade Daten …") }
            } else {
                Canvas(
                    Modifier.fillMaxWidth().height(240.dp).pointerInput(ck, resetN) {
                        twoFingerTransform(
                            onTransform = { cx, zoom, panX ->
                                val span = viewTo - viewFrom
                                val c = cx.coerceIn(0f, 1f)
                                val ns = (span / zoom).coerceIn(0.02f, 1f)
                                val anchor = viewFrom + c * span
                                val nf = (anchor - c * ns - panX * ns).coerceIn(0f, 1f - ns)
                                viewFrom = nf; viewTo = nf + ns
                            },
                            onEnd = { ctl.lastEnd = System.currentTimeMillis() }
                        )
                    }
                ) {
                    drawIntoCanvas {
                        ChartRenderer.draw(
                            it.nativeCanvas, size.width, size.height, density, pts, outlook, col, range, series?.currency ?: "",
                            opts = opts, levels = if (opts.levels) levels else null, viewFrom = viewFrom, viewTo = viewTo
                        )
                    }
                }
            }
        }

        if (!collapsed) {
            val leg = ArrayList<Pair<String, Int>>()
            leg.add("Verlauf" to Pal.PRICE)
            if (opts.mid) leg.add("Erwarteter Verlauf (gestrichelt)" to Pal.MID)
            if (opts.up) leg.add("Positiv-Szenario" to Pal.UP)
            if (opts.down) leg.add("Negativ-Szenario" to Pal.DOWN)
            if (opts.levels) leg.add("Unterstützung/Widerstand" to WHITE)
            if (opts.baseline) leg.add("Ausgangspunkt (grau)" to 0xFF9E9E9E.toInt())
            if (opts.legend) Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                leg.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { row.forEach { Legend(it.first, it.second) } }
                }
            }

            displayed.forEach { s ->
                key(s.id) {
                    val dragging = secDrag == s.id
                    Column(
                        Modifier.zIndex(if (dragging) 2f else 0f)
                            .graphicsLayer {
                                if (dragging) { translationY = secOffset; scaleX = 1.01f; scaleY = 1.01f; alpha = 0.95f }
                            }
                            .onSizeChanged { secHeights[s.id] = it.height }
                            .onGloballyPositioned { secWinTop[s.id] = it.positionInWindow().y }
                    ) {
                        Column(Modifier.padding(top = 12.dp)) {
                            Box(
                                Modifier.fillMaxWidth()
                                    .pointerInput(s.id) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { off ->
                                                secDrag = s.id; secOffset = 0f
                                                ctl.slow = true
                                                ctl.pointerY = (secWinTop[s.id] ?: 0f) + 12f * density + off.y
                                                ctl.onScrolled = { used -> secOffset += used; secSwap() }
                                                ctl.active = true
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                secOffset += amount.y; ctl.pointerY += amount.y
                                                secSwap()
                                            },
                                            onDragEnd = { secDrag = null; secOffset = 0f; ctl.active = false; ctl.lastEnd = System.currentTimeMillis() },
                                            onDragCancel = { secDrag = null; secOffset = 0f; ctl.active = false; ctl.lastEnd = System.currentTimeMillis() }
                                        )
                                    }
                            ) {
                                Text(s.heading, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            s.body()
                        }
                    }
                }
            }

            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (stamp > 0) "Stand " + SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date(stamp)) + (err?.let { " · $it" } ?: "") else "",
                        fontSize = 11.sp
                    )
                    Text("Quelle: " + Api.SOURCE, fontSize = 11.sp)
                }
                TextButton(onClick = onRemove) { Text("Entfernen") }
            }
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
fun Search(fg: Int, watch: List<Watch>, onBack: () -> Unit, onAdd: (Quote) -> Unit) {
    var q by remember { mutableStateOf("") }
    var res by remember { mutableStateOf<List<Quote>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastN by remember { mutableStateOf(0) }
    LaunchedEffect(toastN) { if (toastN > 0) { delay(2000); toast = null } }
    LaunchedEffect(q) {
        if (q.trim().length < 2) { res = emptyList(); return@LaunchedEffect }
        delay(400)
        loading = true
        res = withContext(Dispatchers.IO) { Api.search(q.trim()) }
        loading = false
    }
    Box(Modifier.fillMaxSize()) {
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
                if (loading) "Suche …" else if (q.length >= 2 && res.isEmpty()) "Keine Treffer" else "Mit ＋ hinzufügen, die Suche bleibt offen. WKN-Suche klappt nicht immer, dann ISIN oder Ticker nutzen.",
                fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)
            )
            LazyColumn {
                items(res, key = { it.symbol }) { r ->
                    val inList = watch.any { it.symbol == r.symbol }
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            toast = if (inList) "„${r.name}“ ist schon in der Liste" else { onAdd(r); "Position „${r.name}“ hinzugefügt" }
                            toastN++
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(r.symbol + " · " + r.type, fontSize = 12.sp)
                        }
                        Text(if (inList) "✓" else "＋", fontSize = 22.sp, color = if (inList) Color(Pal.UP) else Color(fg))
                    }
                    HorizontalDivider(color = Color(fg).copy(alpha = 0.12f))
                }
            }
        }
        toast?.let { msg ->
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
                    .clip(RoundedCornerShape(24.dp)).background(Color(0xFFE8EEF7))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) { Text(msg, color = Color.Black, fontSize = 14.sp) }
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
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Settings(
    appBg: Int, appTr: Int, wBg: Int, wTr: Int, store: Store,
    onAppTr: (Int) -> Unit, onWTr: (Int) -> Unit,
    pick: (PickerReq) -> Unit, onAppBg: (Int) -> Unit, onWBg: (Int) -> Unit,
    onReset: () -> Unit, onPrefs: () -> Unit, onRestartAnalysis: () -> Unit, onBack: () -> Unit
) {
    var showMid by remember { mutableStateOf(store.showMid) }
    var showUp by remember { mutableStateOf(store.showUp) }
    var showDown by remember { mutableStateOf(store.showDown) }
    var showLev by remember { mutableStateOf(store.showLevels) }
    var showBase by remember { mutableStateOf(store.showBaseline) }
    var showLeg by remember { mutableStateOf(store.showLegend) }
    var period by remember { mutableStateOf(store.analysisPeriod) }
    var wInt by remember { mutableStateOf(store.widgetIntervalMin) }
    var restarted by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            TextButton(onClick = onBack) { Text("← Zurück") }
            Text("Einstellungen", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }

        Text("Hilfsgraphen im Diagramm", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        SwitchRow("Erwarteter Verlauf (gelb, gestrichelt)", showMid) { showMid = it; store.showMid = it; onPrefs() }
        SwitchRow("Positiv-Szenario (grün)", showUp) { showUp = it; store.showUp = it; onPrefs() }
        SwitchRow("Negativ-Szenario (rot)", showDown) { showDown = it; store.showDown = it; onPrefs() }
        SwitchRow("Unterstützung und Widerstand (weiß)", showLev) { showLev = it; store.showLevels = it; onPrefs() }
        SwitchRow("Ausgangspunkt des Zeitraums (grau)", showBase) { showBase = it; store.showBaseline = it; onPrefs() }
        SwitchRow("Legende unter dem Graphen", showLeg) { showLeg = it; store.showLegend = it; onPrefs() }

        Text("Musteranalyse", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Text("Historie, aus der die Analyse lernt:", fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnaPeriod.values().forEach { p -> Chip(p.label, p == period) { period = p; store.analysisPeriod = p; onPrefs() } }
        }
        Text("Kürzere Zeiträume nutzen feinere Kurse (Stunden-, Minutenkurse). Beim Wechsel startet die Analyse neu.", fontSize = 11.sp)
        Button(onClick = { onRestartAnalysis(); restarted = true }, modifier = Modifier.padding(top = 8.dp)) { Text("Musteranalyse neu starten") }
        if (restarted) Text("Analyse wurde neu gestartet, der Fortschritt steht unter „Basis“.", fontSize = 12.sp)

        Text("Widget", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Text("Aktualisierung alle:", fontSize = 13.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 5, 10, 15, 30, 60).forEach { m ->
                Chip(if (m == 60) "1 Std" else "$m Min", m == wInt) { wInt = m; store.widgetIntervalMin = m; onPrefs() }
            }
        }
        Text("Android kann Aktualisierungen bei aktivem Energiesparen verzögern.", fontSize = 11.sp)

        Text("App-Hintergrund", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Swatch(appBg, "Farbe wählen") { pick(PickerReq("App-Hintergrund", appBg, onAppBg)) }
        Text("Durchsichtigkeit: $appTr %  (0 = deckend, 100 = durchsichtig)", fontSize = 13.sp)
        Slider(appTr.toFloat(), { onAppTr(it.toInt()) }, valueRange = 0f..100f)

        Text("Widget-Hintergrund", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Swatch(wBg, "Farbe wählen") { pick(PickerReq("Widget-Hintergrund", wBg, onWBg)) }
        Text("Durchsichtigkeit: $wTr %  (0 = deckend, 100 = durchsichtig)", fontSize = 13.sp)
        Slider(wTr.toFloat(), { onWTr(it.toInt()) }, valueRange = 0f..100f)

        TextButton(onClick = onReset, modifier = Modifier.padding(top = 8.dp)) { Text("Auf Nachtblau zurücksetzen") }
        Text("Graphfarben sind fest: Verlauf blau, Positiv-Szenario grün, Negativ-Szenario rot.", fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
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
