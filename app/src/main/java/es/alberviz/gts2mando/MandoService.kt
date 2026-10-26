package es.alberviz.gts2mando

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors

class MandoService : Service() {
    companion object {
        const val ACCION_PARAR = "es.alberviz.gts2mando.PARAR"
        private const val CANAL = "mando"
        private const val NOTIF_ID = 1
        private const val ARTISTA = "⏯ creatina · ⏭ ❤ · ⏮ comida"
        private const val MAX_GRABACION_MS = 20_000L
        private const val TITULO_TEMPORAL_MS = 5_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val red = Executors.newSingleThreadExecutor()
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var session: MediaSessionCompat
    private var silencio: AudioTrack? = null
    @Volatile private var silencioActivo = false
    private var recorder: MediaRecorder? = null
    private val audioFile by lazy { File(cacheDir, "comida.ogg") }
    private val volverABase = Runnable { setTitulo(tituloBase()) }
    private val cortarGrabacion = Runnable {
        Eventos.log("corte automático a los 20 s")
        pararYEnviar()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("mando", Context.MODE_PRIVATE)
        session = MediaSessionCompat(this, "gts2mando").apply {
            setCallback(callback)
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
                    .build()
            )
            isActive = true
        }
        setTitulo(tituloBase())
        arrancarPrimerPlano()
        if (prefs.getBoolean("audioSilencioso", false)) arrancarSilencio()
        Eventos.log("servicio iniciado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_PARAR) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        silencioActivo = false
        silencio?.runCatching { stop() }
        silencio?.release()
        silencio = null
        session.isActive = false
        session.release()
        red.shutdown()
        Eventos.log("servicio parado")
        super.onDestroy()
    }

    // --- Botones del reloj ---

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = creatina()
        override fun onPause() = creatina()
        override fun onSkipToNext() = ping()
        override fun onSkipToPrevious() = comida()
    }

    private fun creatina() {
        Eventos.log("⏯ creatina")
        val (url, token) = hub() ?: return
        red.execute {
            val r = Api.creatina(url, token)
            main.post {
                r.onSuccess {
                    prefs.edit().putString("creatinaFecha", LocalDate.now().toString()).apply()
                    Eventos.log("creatina apuntada")
                    setTitulo(tituloBase())
                }.onFailure { error(it) }
            }
        }
    }

    private fun ping() {
        Eventos.log("⏭ ping")
        val url = prefs.getString("pingUrl", "")!!
        val token = prefs.getString("pingToken", "")!!
        val user = prefs.getString("pingUser", "")!!
        if (url.isBlank() || token.isBlank() || user.isBlank()) return error("faltan datos del ping")
        red.execute {
            val r = Api.ping(url, token, user)
            main.post {
                r.onSuccess { tituloTemporal("❤ Enviado") }
                    .onFailure { e ->
                        if (e is HttpError && e.code == 429) {
                            val s = runCatching { JSONObject(e.body).getInt("retry_after_seconds") }.getOrNull()
                            Eventos.log("ping 429, espera $s s")
                            tituloTemporal("⏳ Espera ${s ?: "?"}s")
                        } else error(e)
                    }
            }
        }
    }

    private fun comida() {
        Eventos.log("⏮ comida")
        if (recorder != null) return pararYEnviar()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return error("sin permiso de micro")
        }
        try {
            audioFile.delete()
            @Suppress("DEPRECATION")
            val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(16_000)
            mr.setOutputFile(audioFile.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            return error(e)
        }
        main.removeCallbacks(volverABase)
        main.postDelayed(cortarGrabacion, MAX_GRABACION_MS)
        setTitulo("🎙 Grabando… ⏮ para enviar")
    }

    private fun pararYEnviar() {
        main.removeCallbacks(cortarGrabacion)
        val mr = recorder ?: return
        recorder = null
        val ok = runCatching { mr.stop() }.isSuccess
        mr.release()
        if (!ok) return error("grabación vacía")
        val (url, token) = hub() ?: return
        setTitulo("⏳ Analizando comida…")
        val f = audioFile
        red.execute {
            val r = Api.comida(url, token, f)
            main.post {
                r.onSuccess {
                    Eventos.log("comida enviada (${f.length()} bytes)")
                    tituloTemporal("✓ Comida enviada")
                }.onFailure { error(it) }
            }
        }
    }

    // --- Título en el reloj ---

    private fun hub(): Pair<String, String>? {
        val url = prefs.getString("hubUrl", "")!!
        val token = prefs.getString("hubToken", "")!!
        if (url.isBlank() || token.isBlank()) {
            error("faltan datos del hub")
            return null
        }
        return url to token
    }

    private fun tituloBase(): String =
        if (prefs.getString("creatinaFecha", null) == LocalDate.now().toString()) "💊 Creatina ✓"
        else "💊 Creatina: pendiente"

    private fun setTitulo(titulo: String) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, titulo)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, ARTISTA)
                .build()
        )
        Eventos.log("título: $titulo")
    }

    private fun tituloTemporal(titulo: String) {
        main.removeCallbacks(volverABase)
        setTitulo(titulo)
        if (recorder == null) main.postDelayed(volverABase, TITULO_TEMPORAL_MS)
    }

    private fun error(e: Throwable) = error(
        when (e) {
            is HttpError -> "HTTP ${e.code}"
            is java.net.SocketTimeoutException -> "timeout"
            is java.net.UnknownHostException -> "sin red"
            else -> e.javaClass.simpleName
        }
    )

    private fun error(motivo: String) {
        Eventos.log("error: $motivo")
        tituloTemporal("✗ $motivo")
    }

    // --- Primer plano y audio silencioso ---

    private fun arrancarPrimerPlano() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Mando", NotificationManager.IMPORTANCE_LOW)
        )
        val parar = PendingIntent.getService(
            this, 0, Intent(this, MandoService::class.java).setAction(ACCION_PARAR),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("GTS2 Mando")
            .setContentText(ARTISTA)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", parar)
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
        var tipo = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            tipo = tipo or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notif, tipo)
    }

    private fun arrancarSilencio() {
        val rate = 16_000
        val buf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(buf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        silencio = track
        silencioActivo = true
        track.play()
        Thread {
            val ceros = ByteArray(buf)
            while (silencioActivo) {
                if (track.write(ceros, 0, ceros.size) < 0) break
            }
        }.start()
        Eventos.log("audio silencioso activo")
    }
}
