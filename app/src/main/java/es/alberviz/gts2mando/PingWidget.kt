package es.alberviz.gts2mando

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PingWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_PING = "es.alberviz.gts2mando.WIDGET_PING"
        private const val DURACION_RESULTADO_MS = 3_000L
        private val enviando = AtomicBoolean(false)
    }

    private enum class Estado {
        REPOSO,
        ENVIANDO,
        EXITO,
        RATE_LIMIT,
        ERROR
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PING) {
            // Evita dobles envíos si se toca mientras está enviando
            if (!enviando.compareAndSet(false, true)) {
                return
            }
            val pendingResult = goAsync()
            thread(name = "PingWidgetThread") {
                try {
                    ejecutarPing(context)
                } catch (t: Throwable) {
                    Eventos.log("widget: error ${t.javaClass.simpleName}")
                    actualizarWidgets(context, Estado.ERROR, context.getString(R.string.widget_error))
                    esperarYVolverAReposo(context)
                } finally {
                    enviando.set(false)
                    pendingResult.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        val views = construirViews(context, Estado.REPOSO)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    private fun ejecutarPing(context: Context) {
        Eventos.log("widget: ping")
        actualizarWidgets(context, Estado.ENVIANDO)

        val prefs = context.getSharedPreferences("mando", Context.MODE_PRIVATE)
        val url = prefs.getString("pingUrl", "")?.trim().orEmpty()
        val token = prefs.getString("pingToken", "")?.trim().orEmpty()
        val user = prefs.getString("pingUser", "")?.trim().orEmpty()

        if (url.isEmpty() || token.isEmpty() || user.isEmpty()) {
            Eventos.log("widget: faltan datos")
            actualizarWidgets(context, Estado.ERROR, context.getString(R.string.widget_no_data))
            esperarYVolverAReposo(context)
            return
        }

        val resultado = Api.ping(url, token, user)
        resultado.onSuccess {
            Eventos.log("widget: ping enviado")
            actualizarWidgets(context, Estado.EXITO, context.getString(R.string.widget_sent))
        }.onFailure { e ->
            if (e is HttpError && e.code == 429) {
                val s = runCatching { JSONObject(e.body).getInt("retry_after_seconds") }.getOrNull()
                Eventos.log("widget: 429, espera $s s")
                actualizarWidgets(context, Estado.RATE_LIMIT, formatearEspera(s))
            } else {
                Eventos.log("widget: error ${e.javaClass.simpleName}")
                actualizarWidgets(context, Estado.ERROR, context.getString(R.string.widget_error))
            }
        }

        esperarYVolverAReposo(context)
    }

    private fun esperarYVolverAReposo(context: Context) {
        try {
            Thread.sleep(DURACION_RESULTADO_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        actualizarWidgets(context, Estado.REPOSO)
    }

    private fun formatearEspera(segundos: Int?): String = when {
        segundos == null -> "429"
        segundos < 60 -> "${maxOf(0, segundos)}s"
        else -> "${(segundos + 59) / 60}m"
    }

    private fun actualizarWidgets(context: Context, estado: Estado, texto: String? = null) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val component = ComponentName(context, PingWidget::class.java)
        val views = construirViews(context, estado, texto)
        manager.updateAppWidget(component, views)
    }

    private fun construirViews(context: Context, estado: Estado, texto: String? = null): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_ping)

        val intent = Intent(context, PingWidget::class.java).apply {
            action = ACTION_PING
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        when (estado) {
            Estado.REPOSO -> {
                views.setViewVisibility(R.id.widget_icon, View.VISIBLE)
                views.setViewVisibility(R.id.widget_progress, View.GONE)
                views.setViewVisibility(R.id.widget_text, View.GONE)
                views.setImageViewResource(R.id.widget_icon, R.drawable.widget_ic_heart)
            }
            Estado.ENVIANDO -> {
                views.setViewVisibility(R.id.widget_icon, View.GONE)
                views.setViewVisibility(R.id.widget_progress, View.VISIBLE)
                views.setViewVisibility(R.id.widget_text, View.GONE)
            }
            Estado.EXITO -> {
                views.setViewVisibility(R.id.widget_icon, View.VISIBLE)
                views.setViewVisibility(R.id.widget_progress, View.GONE)
                views.setViewVisibility(R.id.widget_text, View.VISIBLE)
                views.setImageViewResource(R.id.widget_icon, R.drawable.widget_ic_done)
                views.setTextViewText(R.id.widget_text, texto ?: context.getString(R.string.widget_sent))
                views.setTextColor(R.id.widget_text, ContextCompat.getColor(context, R.color.widget_success))
            }
            Estado.RATE_LIMIT -> {
                views.setViewVisibility(R.id.widget_icon, View.VISIBLE)
                views.setViewVisibility(R.id.widget_progress, View.GONE)
                views.setViewVisibility(R.id.widget_text, View.VISIBLE)
                views.setImageViewResource(R.id.widget_icon, R.drawable.widget_ic_wait)
                views.setTextViewText(R.id.widget_text, texto ?: "")
                views.setTextColor(R.id.widget_text, ContextCompat.getColor(context, R.color.widget_wait))
            }
            Estado.ERROR -> {
                views.setViewVisibility(R.id.widget_icon, View.VISIBLE)
                views.setViewVisibility(R.id.widget_progress, View.GONE)
                views.setViewVisibility(R.id.widget_text, View.VISIBLE)
                views.setImageViewResource(R.id.widget_icon, R.drawable.widget_ic_error)
                views.setTextViewText(R.id.widget_text, texto ?: context.getString(R.string.widget_error))
                views.setTextColor(R.id.widget_text, ContextCompat.getColor(context, R.color.widget_error))
            }
        }

        return views
    }
}
