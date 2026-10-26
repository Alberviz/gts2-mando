package es.alberviz.gts2mando

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat

/** Formas de encender el mando sin abrir la app: ajuste rápido, arranque del móvil y aviso «toca para activar». */
object Arranque {
    private const val CANAL = "arranque"
    private const val NOTIF_ID = 2

    fun intentIniciar(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, IniciarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE
    )

    /** Notificación para encenderlo con un toque cuando Android no deja arrancarlo solo. */
    fun avisar(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL, "Activar mando", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(
            NOTIF_ID, NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Mando del reloj apagado")
                .setContentText("Toca para activarlo")
                .setContentIntent(intentIniciar(ctx))
                .setAutoCancel(true)
                .build()
        )
    }

    fun quitarAviso(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)

    fun refrescarTile(ctx: Context) =
        TileService.requestListeningState(ctx, ComponentName(ctx, MandoTile::class.java))
}

/** Actividad invisible: arranca el servicio en primer plano (permitido porque viene de un toque) y se cierra. */
class IniciarActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startForegroundService(Intent(this, MandoService::class.java))
        finish()
    }
}

class MandoTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (MandoService.activo) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        if (MandoService.activo) {
            stopService(Intent(this, MandoService::class.java))
        } else if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(Arranque.intentIniciar(this))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(Intent(this, IniciarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Al encender el móvil (o actualizar la app). Android 15+ prohíbe arrancar desde aquí servicios de audio/micro: aviso. */
class ArranqueReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 35) return Arranque.avisar(ctx)
        runCatching { ctx.startForegroundService(Intent(ctx, MandoService::class.java)) }
            .onFailure { Arranque.avisar(ctx) }
    }
}
