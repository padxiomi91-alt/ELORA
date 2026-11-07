package com.elora.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small local-only diagnostic journal. It never records room codes, tokens, or SDP. */
object EloraDiagnostics {
    private const val FILE_NAME = "elora-diagnostics.txt"
    @Volatile private var appContext: Context? = null
    private val lock = Any()

    fun install(context: Context) {
        appContext = context.applicationContext
        record("Application started")
    }

    fun record(event: String, error: Throwable? = null) {
        val context = appContext ?: return
        synchronized(lock) {
            try {
                val file = File(context.filesDir, FILE_NAME)
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val detail = buildString {
                    append(stamp).append(" [").append(Thread.currentThread().name).append("] ")
                    append(event.replace(Regex("[\\r\\n]"), " "))
                    if (error != null) {
                        append(" | ").append(error.javaClass.name).append(": ")
                        append((error.localizedMessage ?: "no message").replace(Regex("[\\r\\n]"), " "))
                        error.stackTrace.take(12).forEach { append("\n  at ").append(it) }
                    }
                    append("\n")
                }
                file.appendText(detail)
                if (file.length() > 100_000L) {
                    val tail = file.readText().takeLast(60_000)
                    file.writeText(tail)
                }
            } catch (_: Throwable) {
                // Diagnostics must never interrupt camera or signaling work.
            }
        }
    }

    fun read(context: Context): String {
        return try {
            File(context.applicationContext.filesDir, FILE_NAME)
                .takeIf { it.exists() }?.readText() ?: "No ELORA diagnostics recorded yet."
        } catch (error: Throwable) {
            "Could not read diagnostics: ${error.javaClass.simpleName}"
        }
    }
}
