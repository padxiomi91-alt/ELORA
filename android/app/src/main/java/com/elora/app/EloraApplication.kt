package com.elora.app

import android.app.Application
import android.util.Log

class EloraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        EloraDiagnostics.install(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            EloraDiagnostics.record("UNCAUGHT EXCEPTION on ${thread.name}", error)
            Log.e("ELORA", "Uncaught application exception", error)
            previous?.uncaughtException(thread, error)
        }
    }
}
