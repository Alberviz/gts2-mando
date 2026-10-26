# Soporte para Micrófono del Reloj (Amazfit GTS 2 vía Bluetooth SCO) Implementation Plan

> **For Gemini:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Permitir al usuario dictar notas de voz para el registro de comidas directamente desde el micrófono integrado del Amazfit GTS 2 usando el perfil de audio Bluetooth SCO (HFP), activando la pantalla nativa de llamada del reloj y usando el botón «Colgar» como disparador de finalización y envío.

**Architecture:** Se implementa un controlador desacoplado `ScoAudioHelper` que gestiona el enrutamiento bidireccional de audio Bluetooth SCO adaptado para API 29 hasta API 35 (usando `setCommunicationDevice` en Android 12+ con fallback a `startBluetoothSco` en Android 10-11). Se intercepta el evento de colgado (`SCO_AUDIO_STATE_DISCONNECTED`) para detener la grabación en `MandoService`, codificar el audio Opus en OGG (16 kHz mono) y enviarlo al Hub. Se añade un selector en `MainActivity` para alternar entre el micrófono del móvil (por defecto) y el micrófono del reloj, gestionando el permiso runtime `BLUETOOTH_CONNECT`.

**Tech Stack:** Kotlin 2.1.0, Android SDK (minSdk 29, targetSdk 35, Java 17 / 21), Android Jetpack (`androidx.core:core-ktx:1.13.1`, `androidx.media:media:1.7.0`), Android Audio Framework (`AudioManager`, `AudioDeviceInfo`, `MediaRecorder`, `AudioTrack`).

---

### Task 1: Declaración de Permisos de Audio y Bluetooth

**Files:**
- Modify: `app/src/main/AndroidManifest.xml:4-12`

**Step 1: Añadir permisos en el AndroidManifest.xml**

Se requieren:
- `android.permission.MODIFY_AUDIO_SETTINGS`: para cambiar el modo de audio (`MODE_IN_COMMUNICATION`) y enrutar la llamada a SCO.
- `android.permission.BLUETOOTH` (con `maxSdkVersion="30"`): para compatibilidad con Android 10 y 11.
- `android.permission.BLUETOOTH_CONNECT`: permiso en tiempo de ejecución requerido a partir de Android 12 (API 31+) para consultar dispositivos Bluetooth conectados y activar canales de audio SCO.

Editar `app/src/main/AndroidManifest.xml`:

```xml
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

**Step 2: Verificar que el manifiesto procese correctamente**

Ejecutar:
```bash
./gradlew processDebugMainManifest
```
Salida esperada: `BUILD SUCCESSFUL`.

**Step 3: Commit de la tarea 1**

```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(audio): add Bluetooth and audio settings permissions for SCO support"
```

---

### Task 2: Recursos de Strings y Diseño del Selector en la UI

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/layout/activity_main.xml`

**Step 1: Añadir strings en `strings.xml`**

Añadir en `app/src/main/res/values/strings.xml`:

```xml
    <!-- Selector de micrófono -->
    <string name="label_micro_origen">Micrófono para comida</string>
    <string name="micro_phone_label">📱 Micrófono del móvil</string>
    <string name="micro_watch_label">⌚ Micrófono del reloj (Bluetooth SCO)</string>
    <string name="micro_watch_hint">Al pulsar ⏮, el reloj abrirá la pantalla de llamada. Habla y pulsa «Colgar» para enviar.</string>
    <string name="permiso_bt_necesario">Se necesita permiso de dispositivos cercanos para usar el micrófono del reloj</string>
```

**Step 2: Añadir el bloque de selección en `activity_main.xml`**

En `app/src/main/res/layout/activity_main.xml`, dentro de `container_config` (antes del botón `btn_pegar_config` o justo después), añadir el componente:

```xml
                <!-- Selector de micrófono -->
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="@string/label_micro_origen"
                    android:textColor="@color/color_on_surface_secondary"
                    android:textSize="12sp"
                    android:textStyle="bold" />

                <RadioGroup
                    android:id="@+id/rg_micro_origen"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="6dp"
                    android:layout_marginBottom="6dp"
                    android:orientation="vertical">

                    <RadioButton
                        android:id="@+id/rb_micro_phone"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="@string/micro_phone_label"
                        android:textColor="@color/color_on_surface"
                        android:textSize="14sp"
                        android:checked="true" />

                    <RadioButton
                        android:id="@+id/rb_micro_watch"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:text="@string/micro_watch_label"
                        android:textColor="@color/color_on_surface"
                        android:textSize="14sp" />
                </RadioGroup>

                <TextView
                    android:id="@+id/tv_micro_watch_hint"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginBottom="14dp"
                    android:text="@string/micro_watch_hint"
                    android:textColor="@color/color_on_surface_secondary"
                    android:textSize="12sp"
                    android:visibility="gone" />

                <View
                    android:layout_width="match_parent"
                    android:layout_height="1dp"
                    android:layout_marginBottom="14dp"
                    android:background="@color/color_outline" />
```

**Step 3: Verificar compilación del layout**

Ejecutar:
```bash
./gradlew mergeDebugResources
```
Salida esperada: `BUILD SUCCESSFUL`.

**Step 4: Commit de la tarea 2**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/layout/activity_main.xml
git commit -m "feat(ui): add microphone source radio selector and strings in MainActivity"
```

---

### Task 3: Gestión de SharedPreferences y Permisos en `MainActivity.kt`

**Files:**
- Modify: `app/src/main/java/es/alberviz/gts2mando/MainActivity.kt`

**Step 1: Implementar la vinculación de vistas, lectura/guardado de `microOrigen` y petición de `BLUETOOTH_CONNECT`**

Modificar `app/src/main/java/es/alberviz/gts2mando/MainActivity.kt` para:
1. Añadir el permiso `Manifest.permission.BLUETOOTH_CONNECT` al array de permisos iniciales si `Build.VERSION.SDK_INT >= 31`.
2. Leer `"microOrigen"` de SharedPreferences (por defecto `"phone"`).
3. Mostrar/ocultar `tv_micro_watch_hint` según la opción seleccionada.
4. Al seleccionar `rb_micro_watch`, comprobar si se tiene `BLUETOOTH_CONNECT` y solicitarlo en caso contrario.

Código a incorporar en `MainActivity.kt`:

```kotlin
package es.alberviz.gts2mando

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        const val PREF_MICRO_ORIGEN = "microOrigen"
        const val MICRO_PHONE = "phone"
        const val MICRO_WATCH = "watch"
        private const val REQ_PERMISOS = 1
        private const val REQ_BT = 2
    }

    private val campos = listOf("hubUrl", "hubToken", "pingUrl", "pingToken", "pingUser")
    private val edits = mutableMapOf<String, EditText>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var log: TextView
    private lateinit var ivEstadoDot: ImageView
    private lateinit var tvEstadoTitulo: TextView
    private lateinit var tvEstadoSubtitulo: TextView
    private lateinit var btnIniciar: Button
    private lateinit var btnParar: Button
    private lateinit var containerConfig: View
    private lateinit var ivChevronConfig: ImageView
    private lateinit var rgMicroOrigen: RadioGroup
    private lateinit var rbMicroPhone: RadioButton
    private lateinit var rbMicroWatch: RadioButton
    private lateinit var tvMicroWatchHint: TextView
    private var configExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<ScrollView>(R.id.root_scroll).setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        // Estado
        ivEstadoDot = findViewById(R.id.iv_estado_dot)
        tvEstadoTitulo = findViewById(R.id.tv_estado_titulo)
        tvEstadoSubtitulo = findViewById(R.id.tv_estado_subtitulo)
        btnIniciar = findViewById(R.id.btn_iniciar)
        btnParar = findViewById(R.id.btn_parar)

        btnIniciar.setOnClickListener {
            guardar()
            startForegroundService(Intent(this, MandoService::class.java))
            actualizarEstado()
            mainHandler.postDelayed({ actualizarEstado() }, 300)
        }
        btnParar.setOnClickListener {
            stopService(Intent(this, MandoService::class.java))
            actualizarEstado()
            mainHandler.postDelayed({ actualizarEstado() }, 300)
        }

        // Configuración
        val prefs = getSharedPreferences("mando", Context.MODE_PRIVATE)
        edits["hubUrl"] = findViewById(R.id.edit_hub_url)
        edits["hubToken"] = findViewById(R.id.edit_hub_token)
        edits["pingUrl"] = findViewById(R.id.edit_ping_url)
        edits["pingToken"] = findViewById(R.id.edit_ping_token)
        edits["pingUser"] = findViewById(R.id.edit_ping_user)

        for (k in campos) {
            edits[k]?.setText(prefs.getString(k, ""))
        }

        rgMicroOrigen = findViewById(R.id.rg_micro_origen)
        rbMicroPhone = findViewById(R.id.rb_micro_phone)
        rbMicroWatch = findViewById(R.id.rb_micro_watch)
        tvMicroWatchHint = findViewById(R.id.tv_micro_watch_hint)

        val microOrigenActual = prefs.getString(PREF_MICRO_ORIGEN, MICRO_PHONE) ?: MICRO_PHONE
        if (microOrigenActual == MICRO_WATCH) {
            rbMicroWatch.isChecked = true
            tvMicroWatchHint.visibility = View.VISIBLE
        } else {
            rbMicroPhone.isChecked = true
            tvMicroWatchHint.visibility = View.GONE
        }

        rgMicroOrigen.setOnCheckedChangeListener { _, checkedId ->
            val seleccionado = if (checkedId == R.id.rb_micro_watch) MICRO_WATCH else MICRO_PHONE
            prefs.edit().putString(PREF_MICRO_ORIGEN, seleccionado).apply()
            tvMicroWatchHint.visibility = if (seleccionado == MICRO_WATCH) View.VISIBLE else View.GONE

            if (seleccionado == MICRO_WATCH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.permiso_bt_necesario, Toast.LENGTH_LONG).show()
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
                }
            }
        }

        containerConfig = findViewById(R.id.container_config)
        ivChevronConfig = findViewById(R.id.iv_chevron_config)
        val headerConfig = findViewById<View>(R.id.header_config)

        configExpanded = edits.values.all { it.text.isNullOrBlank() }
        actualizarVisibilidadConfig()

        headerConfig.setOnClickListener {
            configExpanded = !configExpanded
            actualizarVisibilidadConfig()
        }

        findViewById<Button>(R.id.btn_pegar_config).setOnClickListener {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            for (linea in clip.lines()) {
                val (k, v) = linea.split("=", limit = 2).takeIf { it.size == 2 } ?: continue
                edits[k.trim()]?.setText(v.trim())
            }
            guardar()
            Toast.makeText(this, R.string.config_pegada, Toast.LENGTH_SHORT).show()
        }

        // Registro
        log = findViewById(R.id.tv_log)

        // Permisos
        val permisos = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permisos.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        requestPermissions(permisos.toTypedArray(), REQ_PERMISOS)

        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        actualizarEstado()
        Eventos.listener = {
            refrescarLog()
            actualizarEstado()
        }
        refrescarLog()
    }

    override fun onPause() {
        guardar()
        Eventos.listener = null
        super.onPause()
    }

    private fun actualizarVisibilidadConfig() {
        containerConfig.visibility = if (configExpanded) View.VISIBLE else View.GONE
        ivChevronConfig.setImageResource(
            if (configExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        )
    }

    private fun actualizarEstado() {
        val activo = MandoService.activo
        if (activo) {
            ivEstadoDot.setImageResource(R.drawable.ic_status_active)
            tvEstadoTitulo.setText(R.string.estado_activo)
            tvEstadoSubtitulo.setText(R.string.estado_subtitulo_activo)
            btnIniciar.alpha = 0.5f
            btnParar.alpha = 1.0f
        } else {
            ivEstadoDot.setImageResource(R.drawable.ic_status_inactive)
            tvEstadoTitulo.setText(R.string.estado_inactivo)
            tvEstadoSubtitulo.setText(R.string.estado_subtitulo_inactivo)
            btnIniciar.alpha = 1.0f
            btnParar.alpha = 0.5f
        }
    }

    private fun refrescarLog() {
        val texto = Eventos.texto()
        log.text = if (texto.isBlank()) getString(R.string.registro_vacio) else texto
    }

    private fun guardar() {
        val ed = getSharedPreferences("mando", Context.MODE_PRIVATE).edit()
        for ((k, e) in edits) ed.putString(k, e.text.toString().trim())
        val micro = if (rbMicroWatch.isChecked) MICRO_WATCH else MICRO_PHONE
        ed.putString(PREF_MICRO_ORIGEN, micro)
        ed.apply()
    }
}
```

**Step 2: Verificar compilación**

Ejecutar:
```bash
./gradlew compileDebugKotlin
```
Salida esperada: `BUILD SUCCESSFUL`.

**Step 3: Commit de la tarea 3**

```bash
git add app/src/main/java/es/alberviz/gts2mando/MainActivity.kt
git commit -m "feat(settings): persist microphone source preference and request BLUETOOTH_CONNECT"
```

---

### Task 4: Crear la Abstracción `ScoAudioHelper.kt`

**Files:**
- Create: `app/src/main/java/es/alberviz/gts2mando/ScoAudioHelper.kt`

**Step 1: Escribir la clase completa `ScoAudioHelper.kt`**

Esta clase encapsula:
1. Comprobación de disponibilidad de dispositivo Bluetooth SCO (con verificación de permisos en API 31+).
2. Manejo del ciclo de vida SCO:
   - En Android 12+ (API 31..35): selección de dispositivo `AudioDeviceInfo.TYPE_BLUETOOTH_SCO` mediante `AudioManager.setCommunicationDevice()`.
   - En Android 10-11 (API 29..30): fallback a `AudioManager.startBluetoothSco()` y `isBluetoothScoOn = true`.
3. Escucha reactiva mediante `BroadcastReceiver` dinámico registrado para `AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED` (con flag `ContextCompat.RECEIVER_EXPORTED` en API 33+).
4. Detección precisa de:
   - Conexión establecida (`SCO_AUDIO_STATE_CONNECTED`) -> el reloj está en la pantalla de llamada y el micro listo.
   - Colgado (`SCO_AUDIO_STATE_DISCONNECTED` tras haber estado conectado) -> el usuario ha pulsado el botón rojo en el GTS 2.
5. Timeout de conexión de 6 segundos para evitar bloqueos si el reloj no responde.
6. Restauración garantizada del modo de audio a `AudioManager.MODE_NORMAL` y liberación de dispositivos de comunicación.

Crear `app/src/main/java/es/alberviz/gts2mando/ScoAudioHelper.kt`:

```kotlin
package es.alberviz.gts2mando

import android.Manifest
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

class ScoAudioHelper(private val context: Context) {

    interface Listener {
        fun onScoConnected(device: AudioDeviceInfo?)
        fun onScoHangup()
        fun onScoError(reason: String)
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var scoReceiver: BroadcastReceiver? = null
    private var isConnected = false
    private var isConnecting = false
    private var originalAudioMode = AudioManager.MODE_NORMAL

    private val timeoutConnect = Runnable {
        if (isConnecting && !isConnected) {
            Eventos.log("SCO timeout al conectar con el reloj")
            val l = listener
            stopSco()
            l?.onScoError("tiempo de espera del reloj agotado")
        }
    }

    fun isScoAvailable(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            return audioManager.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } else {
            @Suppress("DEPRECATION")
            return audioManager.isBluetoothScoAvailableOffCall
        }
    }

    fun getScoDevice(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return null
            }
            return audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }
        return null
    }

    fun startSco(listener: Listener) {
        if (isConnected || isConnecting) stopSco()

        this.listener = listener
        this.isConnecting = true
        this.isConnected = false
        this.originalAudioMode = audioManager.mode

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                isConnecting = false
                listener.onScoError("sin permiso bluetooth")
                return
            }
            val scoDevice = getScoDevice()
            if (scoDevice == null) {
                isConnecting = false
                listener.onScoError("reloj no disponible")
                return
            }
            registrarReceiver()
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            val ok = audioManager.setCommunicationDevice(scoDevice)
            if (!ok) {
                stopSco()
                listener.onScoError("fallo al enrutar audio a SCO")
                return
            }
        } else {
            @Suppress("DEPRECATION")
            if (!audioManager.isBluetoothScoAvailableOffCall) {
                isConnecting = false
                listener.onScoError("reloj no disponible")
                return
            }
            registrarReceiver()
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
        }

        main.postDelayed(timeoutConnect, 6_000L)
    }

    fun stopSco() {
        main.removeCallbacks(timeoutConnect)
        desregistrarReceiver()

        val wasConnected = isConnected
        isConnected = false
        isConnecting = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
        } catch (e: Exception) {
            Eventos.log("error al cerrar SCO: ${e.message}")
        } finally {
            audioManager.mode = originalAudioMode
        }
    }

    private fun registrarReceiver() {
        if (scoReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                val prev = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_PREVIOUS_STATE, -1)
                Eventos.log("SCO estado: $state (previo: $prev)")

                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        main.removeCallbacks(timeoutConnect)
                        isConnecting = false
                        isConnected = true
                        listener?.onScoConnected(getScoDevice())
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        if (isConnected) {
                            // Estaba conectado y el usuario pulsó el botón rojo «Colgar» en el reloj
                            Eventos.log("colgado detectado desde el reloj")
                            isConnected = false
                            isConnecting = false
                            val l = listener
                            stopSco()
                            l?.onScoHangup()
                        } else if (isConnecting) {
                            Eventos.log("desconexión durante enlace SCO")
                        }
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        Eventos.log("SCO estado error")
                        val l = listener
                        stopSco()
                        l?.onScoError("error SCO")
                    }
                }
            }
        }
        scoReceiver = receiver
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun desregistrarReceiver() {
        val r = scoReceiver ?: return
        scoReceiver = null
        try {
            context.unregisterReceiver(r)
        } catch (_: Exception) {}
    }
}
```

**Step 2: Verificar compilación**

Ejecutar:
```bash
./gradlew compileDebugKotlin
```
Salida esperada: `BUILD SUCCESSFUL`.

**Step 3: Commit de la tarea 4**

```bash
git add app/src/main/java/es/alberviz/gts2mando/ScoAudioHelper.kt
git commit -m "feat(audio): implement ScoAudioHelper for Bluetooth SCO lifecycle and hangup detection"
```

---

### Task 5: Integración en `MandoService.kt`

**Files:**
- Modify: `app/src/main/java/es/alberviz/gts2mando/MandoService.kt`

**Step 1: Adaptar `comida()` y `pararYEnviar()` para soportar ambos orígenes de micrófono**

Modificaciones en `MandoService.kt`:
1. Instanciar `private val scoHelper by lazy { ScoAudioHelper(this) }`.
2. Añadir variable de estado `private var grabacionEnCurso = false`.
3. En `comida()`:
   - Leer `prefs.getString("microOrigen", "phone")`.
   - Si `microOrigen == "watch"`:
     - Si ya estamos grabando o conectando: invocar `pararYEnviar()` para detener/enviar inmediatamente.
     - Si no:
       - Verificar permiso `Manifest.permission.RECORD_AUDIO` y en API 31+ `Manifest.permission.BLUETOOTH_CONNECT`.
       - Comprobar `scoHelper.isScoAvailable()`. Si no está disponible, emitir aviso `"✗ Reloj no disponible"` y loguear.
       - Actualizar título a `"⏳ Conectando reloj…"`.
       - Iniciar `scoHelper.startSco(...)`:
         - En `onScoConnected(device)`:
           - Iniciar `MediaRecorder` con `AudioSource.MIC` (o preferido `device` en API 31+).
           - Título: `"🎙 Habla al reloj… (cuelga para enviar)"`.
           - Programar timeout a los 20 segundos (`MAX_GRABACION_MS`).
         - En `onScoHangup()`:
           - El usuario pulsó el botón rojo en el GTS 2 -> llamar a `pararYEnviar()`.
         - En `onScoError(reason)`:
           - Título de error y abortar.
   - Si `microOrigen == "phone"`:
     - Mantener el flujo existente intacto.
4. En `pararYEnviar()`:
   - Detener `MediaRecorder`.
   - Llamar a `scoHelper.stopSco()`.
   - Subir el archivo `comida.ogg` al Hub y restaurar el título del reproductor.
5. En `onDestroy()`:
   - Detener `scoHelper.stopSco()`.

Código completo a integrar en `MandoService.kt`:

```kotlin
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
    private var esperandoSco = false

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
            Arranque.avisar(this)
            stopSelf()
            return
        }
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
        esperandoSco = false
        scoHelper.stopSco()
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
        if (recorder != null || esperandoSco) return pararYEnviar()

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return error("sin permiso de micro")
        }

        val microOrigen = prefs.getString(MainActivity.PREF_MICRO_ORIGEN, MainActivity.MICRO_PHONE)
        if (microOrigen == MainActivity.MICRO_WATCH) {
            iniciarGrabacionReloj()
        } else {
            iniciarGrabacionMovil()
        }
    }

    private fun iniciarGrabacionMovil() {
        if (!iniciarMediaRecorder(null)) return
        main.removeCallbacks(volverABase)
        main.postDelayed(cortarGrabacion, MAX_GRABACION_MS)
        setTitulo("🎙 Grabando… ⏮ para enviar")
    }

    private fun iniciarGrabacionReloj() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return error("sin permiso bluetooth")
        }

        if (!scoHelper.isScoAvailable()) {
            Eventos.log("reloj no conectado para llamadas HFP/SCO")
            return error("reloj no disponible")
        }

        esperandoSco = true
        main.removeCallbacks(volverABase)
        setTitulo("⏳ Conectando reloj…")

        scoHelper.startSco(object : ScoAudioHelper.Listener {
            override fun onScoConnected(device: AudioDeviceInfo?) {
                esperandoSco = false
                if (!iniciarMediaRecorder(device)) {
                    scoHelper.stopSco()
                    return
                }
                main.removeCallbacks(volverABase)
                main.postDelayed(cortarGrabacion, MAX_GRABACION_MS)
                setTitulo("🎙 Habla al reloj… (cuelga para enviar)")
                Eventos.log("grabando desde micro del reloj")
            }

            override fun onScoHangup() {
                Eventos.log("corte por colgado en reloj")
                esperandoSco = false
                pararYEnviar()
            }

            override fun onScoError(reason: String) {
                esperandoSco = false
                error(reason)
            }
        })
    }

    private fun iniciarMediaRecorder(dispositivo: AudioDeviceInfo?): Boolean {
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
            if (dispositivo != null && Build.VERSION.SDK_INT >= 31) {
                mr.setPreferredDevice(dispositivo)
            }
            mr.prepare()
            mr.start()
            recorder = mr
            return true
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            error(e)
            return false
        }
    }

    private fun pararYEnviar() {
        main.removeCallbacks(cortarGrabacion)
        esperandoSco = false
        scoHelper.stopSco()

        val mr = recorder
        recorder = null
        if (mr == null) {
            setTitulo(tituloBase())
            return
        }

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
        if (recorder == null && !esperandoSco) main.postDelayed(volverABase, TITULO_TEMPORAL_MS)
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
```

**Step 2: Verificar compilación completa**

Ejecutar:
```bash
./gradlew compileDebugKotlin
```
Salida esperada: `BUILD SUCCESSFUL`.

**Step 3: Commit de la tarea 5**

```bash
git add app/src/main/java/es/alberviz/gts2mando/MandoService.kt
git commit -m "feat(service): integrate ScoAudioHelper into food dictation workflow"
```

---

### Task 6: Verificación Global y Generación del APK

**Files:**
- Output: `app/build/outputs/apk/debug/app-debug.apk`

**Step 1: Compilar el APK de depuración**

Ejecutar:
```bash
./gradlew assembleDebug
```
Salida esperada: `BUILD SUCCESSFUL` con `app-debug.apk` generado en `app/build/outputs/apk/debug/app-debug.apk`.

**Step 2: Validar permisos empaquetados en el APK**

Ejecutar:
```bash
unzip -p app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | strings | grep -iE "BLUETOOTH|AUDIO|RECORD"
```
Salida esperada: Presencia de `BLUETOOTH_CONNECT`, `MODIFY_AUDIO_SETTINGS`, `RECORD_AUDIO`.

**Step 3: Actualizar el APK raíz de instalación si aplica**

Ejecutar:
```bash
cp app/build/outputs/apk/debug/app-debug.apk gts2-mando.apk
```

**Step 4: Commit final**

```bash
git add gts2-mando.apk
git commit -m "chore(release): update debug APK with watch microphone Bluetooth SCO support"
```
