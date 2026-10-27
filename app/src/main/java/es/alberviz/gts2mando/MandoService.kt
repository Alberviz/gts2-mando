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
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
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
        @Volatile var activo = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val red = Executors.newSingleThreadExecutor()
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var session: MediaSessionCompat
    private var silencio: AudioTrack? = null
    @Volatile private var silencioActivo = false
    private var recorder: MediaRecorder? = null
    private val audioFile by lazy { File(cacheDir, "comida.ogg") }
    private val scoHelper by lazy { ScoAudioHelper(this) }
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
        if (!arrancarPrimerPlano()) {
            // Android no deja ponerlo en primer plano desde segundo plano (p. ej. al relanzarlo): pedir un toque.
            Arranque.avisar(this)
            stopSelf()
            return
        }
        // Sin audio real Android no entrega los botones del reloj a esta sesión.
        arrancarSilencio()
        activo = true
        Arranque.quitarAviso(this)
        Arranque.refrescarTile(this)
        Eventos.log("servicio iniciado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_PARAR) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        activo = false
        Arranque.refrescarTile(this)
        main.removeCallbacksAndMessages(null)
        recorder?.runCatching { stop() }
        recorder?.release()
        recorder = null
        scoHelper.stopSco()
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
        override fun onPlay() {
            if (recorder != null || scoHelper.isActive) return finalizarOCancelarGrabacion()
            creatina()
        }
        override fun onPause() {
            if (recorder != null || scoHelper.isActive) return finalizarOCancelarGrabacion()
            creatina()
        }
        override fun onSkipToNext() {
            if (recorder != null || scoHelper.isActive) {
                Eventos.log("⏭ ping ignorado: grabación en curso")
                return
            }
            ping()
        }
        override fun onSkipToPrevious() = comida()
    }

    private fun finalizarOCancelarGrabacion() {
        if (scoHelper.isActive && !scoHelper.isConnected) {
            abortarGrabacion("cancelado")
        } else {
            pararYEnviar()
        }
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
        if (recorder != null || scoHelper.isActive) return finalizarOCancelarGrabacion()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return error("sin permiso de micro")
        }
        val origen = prefs.getString(MainActivity.PREF_MICRO_ORIGEN, MainActivity.MICRO_PHONE) ?: MainActivity.MICRO_PHONE
        if (origen == MainActivity.MICRO_PHONE) {
            if (iniciarRecorder(MediaRecorder.AudioSource.MIC, null, reportarError = true)) {
                main.removeCallbacks(volverABase)
                main.postDelayed(cortarGrabacion, MAX_GRABACION_MS)
                setTitulo("🎙 Grabando… ⏮ para enviar")
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    return error("sin permiso bluetooth")
                }
            }
            if (!scoHelper.isScoAvailable()) {
                val inputs = getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                    .map { "${it.productName}(tipo ${it.type})" }
                Eventos.log("SCO no disponible. Entradas detectadas: $inputs")
                return error("reloj sin BT de llamadas")
            }
            main.removeCallbacks(volverABase)
            setTitulo("⏳ Conectando reloj…")
            scoHelper.startSco(object : ScoAudioHelper.Listener {
                override fun onStartRecorder(device: AudioDeviceInfo?): Boolean {
                    return iniciarRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, device, reportarError = false)
                }

                override fun onScoConnected() {
                    main.removeCallbacks(cortarGrabacion)
                    main.postDelayed(cortarGrabacion, MAX_GRABACION_MS)
                    main.removeCallbacks(volverABase)
                    setTitulo("🎙 Habla al reloj… (cuelga para enviar)")
                    Eventos.log("grabando desde micro del reloj")
                }

                override fun onScoHangup() {
                    Eventos.log("corte por colgado en reloj")
                    pararYEnviar()
                }

                override fun onScoError(reason: String) {
                    abortarGrabacion(reason)
                }
            })
        }
    }

    private fun iniciarRecorder(
        audioSource: Int,
        preferredDevice: AudioDeviceInfo?,
        reportarError: Boolean = true
    ): Boolean {
        audioFile.delete()
        @Suppress("DEPRECATION")
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        return try {
            mr.setAudioSource(audioSource)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && preferredDevice != null) {
                mr.setPreferredDevice(preferredDevice)
            }
            mr.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(16_000)
            mr.setOutputFile(audioFile.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            true
        } catch (e: Exception) {
            Eventos.log("fallo MediaRecorder: ${e.javaClass.simpleName}")
            mr.runCatching { stop() }
            mr.release()
            recorder = null
            if (reportarError) error(e)
            false
        }
    }

    private fun pararYEnviar() {
        main.removeCallbacks(cortarGrabacion)
        val mr = recorder
        recorder = null
        if (mr == null) {
            scoHelper.stopSco()
            setTitulo(tituloBase())
            return
        }
        val ok = runCatching { mr.stop() }.isSuccess
        mr.release()
        scoHelper.stopSco()
        if (!ok || !audioFile.exists() || audioFile.length() == 0L) {
            return error("grabación vacía")
        }
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

    private fun abortarGrabacion(motivo: String) {
        main.removeCallbacks(cortarGrabacion)
        val mr = recorder
        recorder = null
        mr?.runCatching { stop() }
        mr?.release()
        scoHelper.stopSco()
        audioFile.delete()
        error(motivo)
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
        if (recorder == null && !scoHelper.isActive) main.postDelayed(volverABase, TITULO_TEMPORAL_MS)
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

    /** false si Android no deja ponerlo en primer plano ni siquiera sin micro. */
    private fun arrancarPrimerPlano(): Boolean {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CANAL, "Mando", NotificationManager.IMPORTANCE_LOW)
        )
        val parar = PendingIntent.getService(
            this, 0, Intent(this, MandoService::class.java).setAction(ACCION_PARAR),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("GTS2 Mando")
            .setContentText(ARTISTA)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", parar)
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
        val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val conMicro = media or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val tieneMicro = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // El micro solo se concede si se arranca desde un toque; si no, sigue sin él (la comida fallará).
        for (tipo in if (tieneMicro) listOf(conMicro, media) else listOf(media)) {
            val ok = runCatching { ServiceCompat.startForeground(this, NOTIF_ID, notif, tipo) }
                .onFailure { Eventos.log("primer plano rechazado: ${it.javaClass.simpleName}") }
                .isSuccess
            if (ok) return true
        }
        return false
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
