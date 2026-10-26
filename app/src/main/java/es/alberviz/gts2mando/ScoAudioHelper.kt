package es.alberviz.gts2mando

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Gestor del canal de audio Bluetooth SCO para el Amazfit GTS 2 (HFP/SCO).
 *
 * Compatible con Android 10 a Android 15 (API 29 a 35).
 *
 * En Android 12+ (API 31+), Android utiliza "Lazy SCO Routing Policy": invocar
 * [AudioManager.setCommunicationDevice] configura la ruta pero NO levanta físicamente
 * el enlace de radio Bluetooth SCO hasta que la aplicación arranca una grabación activa con
 * [android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION].
 *
 * En Android 10-11 (API 29-30), [AudioManager.startBluetoothSco] levanta el enlace antes
 * o durante la captura de audio.
 */
@SuppressLint("MissingPermission")
class ScoAudioHelper(private val context: Context) {

    interface Listener {
        /**
         * Llamado cuando `MandoService` debe crear y arrancar el MediaRecorder (con VOICE_COMMUNICATION).
         * En API 31+ se invoca inmediatamente tras `setCommunicationDevice(scoDevice)` para que Android
         * levante el enlace físico HFP/SCO hacia el reloj.
         * En API 29-30 se invoca al recibir `SCO_AUDIO_STATE_CONNECTED` / `STATE_AUDIO_CONNECTED`.
         * Debe devolver `true` si el MediaRecorder arrancó bien, o `false` si falló.
         */
        fun onStartRecorder(device: AudioDeviceInfo?): Boolean

        /** Llamado cuando el enlace de radio HFP/SCO con el reloj ya está conectado (reloj en pantalla de llamada). */
        fun onScoConnected()

        /** Llamado UNA SOLA VEZ cuando el usuario cuelga la llamada en el reloj (el enlace SCO pasa de conectado a desconectado). */
        fun onScoHangup()

        /** Llamado si falla la conexión o vence el timeout sin conectar. */
        fun onScoError(reason: String)
    }

    companion object {
        private const val TIMEOUT_CONNECT_MS = 8_000L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    private var active = false
    private var isConnecting = false
    private var isRfConnected = false
    private var recorderStarted = false
    private var originalAudioMode = AudioManager.MODE_NORMAL
    private var currentListener: Listener? = null

    private var isReceiverRegistered = false
    private var isCommListenerRegistered = false
    private var commListener: AudioManager.OnCommunicationDeviceChangedListener? = null

    val isActive: Boolean
        get() = active

    val isConnected: Boolean
        get() = isRfConnected

    private val timeoutConnect = Runnable {
        if (active && !isRfConnected) {
            Eventos.log("SCO timeout al conectar con el reloj")
            val l = currentListener
            stopSco()
            l?.onScoError("reloj sin respuesta")
        }
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(
                        AudioManager.EXTRA_SCO_AUDIO_STATE,
                        AudioManager.SCO_AUDIO_STATE_ERROR
                    )
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            handleScoConnected("AudioManager")
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            handleScoDisconnected("AudioManager")
                        }
                        AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                            Eventos.log("SCO conectando (AudioManager)")
                        }
                        AudioManager.SCO_AUDIO_STATE_ERROR -> {
                            if (active && !isRfConnected) {
                                Eventos.log("SCO error de audio (AudioManager)")
                                val l = currentListener
                                stopSco()
                                l?.onScoError("error SCO")
                            }
                        }
                    }
                }
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE,
                        BluetoothHeadset.STATE_AUDIO_DISCONNECTED
                    )
                    when (state) {
                        BluetoothHeadset.STATE_AUDIO_CONNECTED -> {
                            handleScoConnected("BluetoothHeadset")
                        }
                        BluetoothHeadset.STATE_AUDIO_DISCONNECTED -> {
                            handleScoDisconnected("BluetoothHeadset")
                        }
                        BluetoothHeadset.STATE_AUDIO_CONNECTING -> {
                            Eventos.log("SCO conectando (BluetoothHeadset)")
                        }
                    }
                }
            }
        }
    }

    /**
     * Comprueba si el dispositivo soporta y tiene disponible un enlace Bluetooth SCO (reloj conectado).
     */
    fun isScoAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBtDevice = inputs.any { it.isBluetoothDevice() } || outputs.any { it.isBluetoothDevice() }
        if (hasBtDevice) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (audioManager.availableCommunicationDevices.any { it.isBluetoothDevice() }) return true
        }

        @Suppress("DEPRECATION")
        return audioManager.isBluetoothScoAvailableOffCall
    }

    private fun AudioDeviceInfo.isBluetoothDevice(): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }

    /**
     * En Android 12+ (API 31+) busca el AudioDeviceInfo tipo TYPE_BLUETOOTH_SCO o TYPE_BLE_HEADSET disponible.
     * En Android 10-11 devuelve null.
     */
    fun getScoDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return null
            }
            return audioManager.availableCommunicationDevices.firstOrNull {
                it.isBluetoothDevice()
            }
        }
        return null
    }

    /**
     * Inicia el proceso de conexión de audio Bluetooth SCO con el reloj.
     */
    fun startSco(listener: Listener) {
        if (active || isConnecting) {
            stopSco()
        }

        currentListener = listener
        active = true
        isConnecting = true
        isRfConnected = false
        recorderStarted = false

        originalAudioMode = audioManager.mode

        // Registra BroadcastReceiver para ambos intents (máxima compatibilidad entre OEMs)
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            context,
            scoReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        isReceiverRegistered = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 1. Establecer MODE_IN_COMMUNICATION primero: Android requiere este modo para enumerar y enrutar SCO
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            val scoDevice = getScoDevice()
            if (scoDevice != null) {
                Eventos.log("SCO: dispositivo encontrado (${scoDevice.productName}, tipo ${scoDevice.type})")
                val ok = audioManager.setCommunicationDevice(scoDevice)
                if (ok) {
                    val commDevListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                        if (device == null || !device.isBluetoothDevice()) {
                            handleScoDisconnected("CommDeviceChanged")
                        }
                    }
                    commListener = commDevListener
                    audioManager.addOnCommunicationDeviceChangedListener(
                        ContextCompat.getMainExecutor(context),
                        commDevListener
                    )
                    isCommListenerRegistered = true

                    // Lazy SCO Routing: arrancar MediaRecorder para que Android levante la radio SCO
                    if (!listener.onStartRecorder(scoDevice)) {
                        Eventos.log("SCO: onStartRecorder devolvió false en startSco")
                        val l = currentListener
                        stopSco()
                        l?.onScoError("fallo al iniciar micro")
                        return
                    }
                    recorderStarted = true
                    main.postDelayed(timeoutConnect, TIMEOUT_CONNECT_MS)
                    return
                } else {
                    Eventos.log("SCO: setCommunicationDevice falló, intentando fallback startBluetoothSco")
                }
            } else {
                val devList = audioManager.availableCommunicationDevices.joinToString { "${it.productName}(tipo ${it.type})" }
                Eventos.log("SCO: no encontrado en comm devices [$devList], intentando fallback startBluetoothSco")
            }

            // Fallback para Android 12+: si setCommunicationDevice no está disponible en este OEM,
            // usar el mecanismo tradicional de SCO virtual
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            main.postDelayed(timeoutConnect, TIMEOUT_CONNECT_MS)
        } else {
            // 1. Comprueba isBluetoothScoAvailableOffCall
            @Suppress("DEPRECATION")
            if (!audioManager.isBluetoothScoAvailableOffCall) {
                Eventos.log("SCO: isBluetoothScoAvailableOffCall es false")
                val l = currentListener
                stopSco()
                l?.onScoError("SCO no disponible")
                return
            }

            // 2. audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // 3. startBluetoothSco() y isBluetoothScoOn = true
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true

            // 4. Programa timeoutConnect a 8 s. El recorder arrancará en onScoConnected al recibir la confirmación RF.
            main.postDelayed(timeoutConnect, TIMEOUT_CONNECT_MS)
        }
    }

    private fun handleScoConnected(source: String) {
        if (!isRfConnected && active) {
            main.removeCallbacks(timeoutConnect)
            isRfConnected = true
            isConnecting = false
            Eventos.log("SCO conectado ($source)")

            // En Android 10-11, el recorder se arranca aquí al confirmar la conexión RF física
            if (!recorderStarted) {
                val ok = currentListener?.onStartRecorder(getScoDevice()) ?: false
                if (!ok) {
                    Eventos.log("SCO: onStartRecorder devolvió false tras conectar RF")
                    val l = currentListener
                    stopSco()
                    l?.onScoError("fallo al iniciar micro")
                    return
                }
                recorderStarted = true
            }

            currentListener?.onScoConnected()
        }
    }

    private fun handleScoDisconnected(source: String) {
        // Solo reaccionar si el enlace RF estaba efectivamente conectado y activo.
        // Esto ignora el broadcast sticky inicial de desconexión al registrar el receiver.
        if (isRfConnected && active) {
            Eventos.log("SCO colgado desde el reloj ($source)")
            // Idempotencia absoluta: marcar como no conectado y no activo ANTES de invocar stopSco y el callback
            isRfConnected = false
            active = false
            val l = currentListener
            stopSco()
            l?.onScoHangup()
        }
    }

    /**
     * Detiene y limpia de forma segura e idempotente cualquier conexión SCO activa,
     * desregistrando listeners y restaurando el modo de audio normal.
     */
    fun stopSco() {
        active = false
        isConnecting = false
        isRfConnected = false
        recorderStarted = false
        currentListener = null

        main.removeCallbacks(timeoutConnect)

        // Desregistrar receiver y listener de comunicación ANTES de limpiar la ruta
        // para asegurar que el cierre iniciado localmente no genere eventos de desconexión espurios.
        if (isReceiverRegistered) {
            runCatching { context.unregisterReceiver(scoReceiver) }
            isReceiverRegistered = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isCommListenerRegistered && commListener != null) {
                runCatching { audioManager.removeOnCommunicationDeviceChangedListener(commListener!!) }
                commListener = null
                isCommListenerRegistered = false
            }
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        }

        val targetMode = if (originalAudioMode == AudioManager.MODE_IN_COMMUNICATION) {
            AudioManager.MODE_NORMAL
        } else {
            originalAudioMode
        }
        if (audioManager.mode != targetMode) {
            audioManager.mode = targetMode
        }
    }
}
