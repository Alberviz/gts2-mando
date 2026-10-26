package es.alberviz.gts2mando

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val campos = listOf("hubUrl", "hubToken", "pingUrl", "pingToken", "pingUser")
    private val edits = mutableMapOf<String, EditText>()
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("mando", Context.MODE_PRIVATE)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        for (k in campos) {
            col.addView(TextView(this).apply { text = k })
            val e = EditText(this).apply {
                setText(prefs.getString(k, ""))
                inputType = InputType.TYPE_CLASS_TEXT or
                    if (k.endsWith("Token")) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
            }
            edits[k] = e
            col.addView(e)
        }
        // Rellena los campos desde el portapapeles: una línea «clave=valor» por campo.
        col.addView(Button(this).apply {
            text = "Pegar config"
            setOnClickListener {
                val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty()
                for (linea in clip.lines()) {
                    val (k, v) = linea.split("=", limit = 2).takeIf { it.size == 2 } ?: continue
                    edits[k.trim()]?.setText(v.trim())
                }
                guardar()
            }
        })
        col.addView(Button(this).apply {
            text = "Iniciar"
            setOnClickListener {
                guardar()
                startForegroundService(Intent(this@MainActivity, MandoService::class.java))
            }
        })
        col.addView(Button(this).apply {
            text = "Parar"
            setOnClickListener { stopService(Intent(this@MainActivity, MandoService::class.java)) }
        })
        log = TextView(this)
        col.addView(log)
        // Android 15 dibuja bajo las barras del sistema: sin esto la barra de estado tapa los primeros campos.
        setContentView(ScrollView(this).apply {
            addView(col)
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        })

        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 1)
        // Que el ahorro de batería no mate el servicio en segundo plano.
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        Eventos.listener = { log.text = Eventos.texto() }
        log.text = Eventos.texto()
    }

    override fun onPause() {
        guardar()
        Eventos.listener = null
        super.onPause()
    }

    private fun guardar() {
        val ed = getSharedPreferences("mando", Context.MODE_PRIVATE).edit()
        for ((k, e) in edits) ed.putString(k, e.text.toString().trim())
        ed.apply()
    }
}
