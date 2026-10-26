package es.alberviz.gts2mando

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Log en memoria para ver el último evento en la pantalla sin el reloj. */
object Eventos {
    private val lineas = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    var listener: (() -> Unit)? = null

    fun log(msg: String) {
        Log.i("mando", msg)
        val hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        synchronized(lineas) {
            lineas.addFirst("$hora $msg")
            while (lineas.size > 30) lineas.removeLast()
        }
        main.post { listener?.invoke() }
    }

    fun texto(): String = synchronized(lineas) { lineas.joinToString("\n") }
}
