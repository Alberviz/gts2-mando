package es.alberviz.gts2mando

import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpError(val code: Int, val body: String) : Exception("HTTP $code")

/** Llamadas HTTP bloqueantes: ejecutarlas siempre en el executor. */
object Api {
    private const val TIMEOUT_MS = 15_000

    fun creatina(hubUrl: String, hubToken: String): Result<String> =
        request("POST", "${hubUrl.trimEnd('/')}/api/mando/creatina", hubToken, null)

    fun ping(pingUrl: String, token: String, user: String): Result<String> =
        request("GET", "${pingUrl.trimEnd('/')}/api/widget/ping?token=${enc(token)}&user=${enc(user)}", null, null)

    fun comida(hubUrl: String, hubToken: String, audio: File): Result<String> =
        request("POST", "${hubUrl.trimEnd('/')}/api/mando/comida", hubToken, audio)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun request(method: String, url: String, bearer: String?, audio: File?): Result<String> = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (method == "POST") {
                conn.doOutput = true
                val bytes = audio?.readBytes() ?: ByteArray(0)
                if (audio != null) conn.setRequestProperty("Content-Type", "audio/ogg")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Log.i("mando", "$method ${url.substringBefore('?')} -> $code")
            if (code !in 200..299) throw HttpError(code, body)
            body
        } finally {
            conn.disconnect()
        }
    }
}
