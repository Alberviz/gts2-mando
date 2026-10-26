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
    private var configExpanded = false
    private lateinit var rgMicroOrigen: RadioGroup
    private lateinit var rbMicroPhone: RadioButton
    private lateinit var rbMicroWatch: RadioButton
    private lateinit var tvMicroWatchHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 15 dibuja bajo las barras del sistema: sin esto la barra de estado tapa los primeros campos.
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

        // Selector de micrófono
        rgMicroOrigen = findViewById(R.id.rg_micro_origen)
        rbMicroPhone = findViewById(R.id.rb_micro_phone)
        rbMicroWatch = findViewById(R.id.rb_micro_watch)
        tvMicroWatchHint = findViewById(R.id.tv_micro_watch_hint)

        val microOrigen = prefs.getString(PREF_MICRO_ORIGEN, MICRO_PHONE) ?: MICRO_PHONE
        if (microOrigen == MICRO_WATCH) {
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
            Eventos.log("micrófono: ${if (seleccionado == MICRO_WATCH) "reloj (SCO)" else "móvil"}")
            if (seleccionado == MICRO_WATCH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.permiso_bt_necesario, Toast.LENGTH_SHORT).show()
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
                }
            }
        }

        containerConfig = findViewById(R.id.container_config)
        ivChevronConfig = findViewById(R.id.iv_chevron_config)
        val headerConfig = findViewById<View>(R.id.header_config)

        // Si están todos vacíos (primer uso), mostramos la configuración expandida.
        configExpanded = edits.values.all { it.text.isNullOrBlank() }
        actualizarVisibilidadConfig()

        headerConfig.setOnClickListener {
            configExpanded = !configExpanded
            actualizarVisibilidadConfig()
        }

        // Rellena los campos desde el portapapeles: una línea «clave=valor» por campo.
        findViewById<Button>(R.id.btn_pegar_config).setOnClickListener {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            for (linea in clip.lines()) {
                val (k, v) = linea.split("=", limit = 2).takeIf { it.size == 2 } ?: continue
                val clave = k.trim()
                val valor = v.trim()
                if (clave == PREF_MICRO_ORIGEN) {
                    if (valor.equals(MICRO_WATCH, ignoreCase = true)) {
                        rgMicroOrigen.check(R.id.rb_micro_watch)
                    } else if (valor.equals(MICRO_PHONE, ignoreCase = true)) {
                        rgMicroOrigen.check(R.id.rb_micro_phone)
                    }
                } else {
                    edits[clave]?.setText(valor)
                }
            }
            guardar()
            Toast.makeText(this, R.string.config_pegada, Toast.LENGTH_SHORT).show()
        }

        // Registro
        log = findViewById(R.id.tv_log)

        // Permisos y optimización de batería
        val permisos = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permisos.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        requestPermissions(permisos.toTypedArray(), REQ_PERMISOS)
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT) {
            val concedido = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!concedido) {
                rgMicroOrigen.check(R.id.rb_micro_phone)
                Toast.makeText(this, R.string.permiso_bt_necesario, Toast.LENGTH_LONG).show()
            }
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
        ed.putString(PREF_MICRO_ORIGEN, if (rbMicroWatch.isChecked) MICRO_WATCH else MICRO_PHONE)
        ed.apply()
    }
}
