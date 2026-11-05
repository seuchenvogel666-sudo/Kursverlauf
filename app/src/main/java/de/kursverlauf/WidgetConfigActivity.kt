package de.kursverlauf

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Wird beim Platzieren des Widgets angezeigt: Welcher Graph, welcher Zeitraum? */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val store = Store(this)
        val bg = store.appBg
        val fg = textColorFor(bg)
        setContent {
            var range by remember { mutableStateOf(Range.D1) }
            MaterialTheme(colorScheme = if (fg == WHITE) darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalContentColor provides Color(fg)) {
                    Column(Modifier.fillMaxSize().background(Color(bg)).systemBarsPadding().padding(16.dp)) {
                        Text("Widget: Graph wählen", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Zeitraum", Modifier.padding(top = 12.dp), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            Range.values().forEach { Chip(it.short, it == range) { range = it } }
                        }
                        val list = store.watch
                        if (list.isEmpty()) Text("Erst in der App einen Graphen hinzufügen.")
                        LazyColumn {
                            items(list, key = { it.symbol }) { w ->
                                Column(Modifier.fillMaxWidth().clickable {
                                    store.setWidget(id, w.symbol, range)
                                    ChartWidget.updateOne(this@WidgetConfigActivity, id)
                                    ChartWidget.schedule(this@WidgetConfigActivity)
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                    finish()
                                }.padding(vertical = 12.dp)) {
                                    Text(w.name, fontWeight = FontWeight.Bold)
                                    Text(w.symbol, fontSize = 12.sp)
                                }
                                HorizontalDivider(color = Color(fg).copy(alpha = 0.12f))
                            }
                        }
                    }
                }
            }
        }
    }
}
