package de.kursverlauf

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.RemoteViews
import kotlin.concurrent.thread
import kotlin.math.sqrt

class ChartWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { refresh(ctx, ids); schedule(ctx) }
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: android.os.Bundle) { refresh(ctx, intArrayOf(id)) }
    override fun onEnabled(ctx: Context) { schedule(ctx) }
    override fun onDisabled(ctx: Context) { cancel(ctx) }
    override fun onDeleted(ctx: Context, ids: IntArray) { val s = Store(ctx); ids.forEach { s.removeWidget(it) } }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == ACTION_TICK) {
            val ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, ChartWidget::class.java))
            if (ids.isNotEmpty()) { refresh(ctx, ids); schedule(ctx) }
        } else super.onReceive(ctx, intent)
    }

    private fun refresh(ctx: Context, ids: IntArray) {
        val pending = goAsync()
        val app = ctx.applicationContext
        thread {
            try {
                val m = AppWidgetManager.getInstance(app)
                ids.forEach { runCatching { render(app, m, it) } }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_TICK = "de.kursverlauf.TICK"

        private fun pi(ctx: Context) = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, ChartWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        /** Minütliche Aktualisierung (Android darf bei Doze/Energiesparen etwas verzögern). */
        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.RTC, System.currentTimeMillis() + 60_000, pi(ctx))
        }
        fun cancel(ctx: Context) { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(ctx)) }

        fun refreshAll(ctx: Context) {
            val app = ctx.applicationContext
            thread {
                val m = AppWidgetManager.getInstance(app)
                m.getAppWidgetIds(ComponentName(app, ChartWidget::class.java)).forEach { runCatching { render(app, m, it) } }
            }
        }
        fun updateOne(ctx: Context, id: Int) {
            val app = ctx.applicationContext
            thread { runCatching { render(app, AppWidgetManager.getInstance(app), id) } }
        }

        fun render(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val st = Store(ctx)
            val sym = st.widgetSymbol(id) ?: return
            val range = st.widgetRange(id)
            val ser = runCatching { Api.chart(sym, range) }.getOrNull()
            val watch = st.watch.find { it.symbol == sym }
            val th = Themes.all[st.themeIdx]
            val opts = mgr.getAppWidgetOptions(id)
            var d = ctx.resources.displayMetrics.density
            var wp = (opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) * d)
            var hp = (opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) * d)
            val maxPx = 320_000f
            if (wp * hp > maxPx) { val f = sqrt(maxPx / (wp * hp)); wp *= f; hp *= f; d *= f }

            val bgFull = st.widgetBg
            val fg = textColorFor(bgFull)
            val col = ChartColors(
                watch?.price ?: th.price, watch?.up ?: th.up, watch?.down ?: th.down,
                fg, withTransparency(bgFull, st.widgetTransp)
            )
            val pts = ser?.points ?: emptyList()
            val outlook = Forecast.compute(pts)
            var right: String? = null; var rc = fg
            if (pts.size >= 2) {
                val chg = pts.last().v / pts.first().v - 1
                right = ChartRenderer.fmt(pts.last().v) + "  " + String.format(java.util.Locale.GERMANY, "%+.2f %%", chg * 100)
                rc = if (chg >= 0) col.up else col.down
            }
            val bmp = Bitmap.createBitmap(wp.toInt().coerceAtLeast(50), hp.toInt().coerceAtLeast(50), Bitmap.Config.ARGB_8888)
            ChartRenderer.draw(
                Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), d, pts, outlook, col, range,
                ser?.currency ?: "", title = watch?.name ?: sym, right = right, rightColor = rc, bgCorner = 16f * d
            )
            val rv = RemoteViews(ctx.packageName, R.layout.widget_chart)
            rv.setImageViewBitmap(R.id.img, bmp)
            rv.setOnClickPendingIntent(
                R.id.img,
                PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            mgr.updateAppWidget(id, rv)
        }
    }
}
