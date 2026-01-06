package com.pasiflonet.mobile.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {
    private const val FILE_NAME = "last_crash.txt"

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val f = File(ctx.cacheDir, FILE_NAME)
                f.writeText(buildString {
                    append("=== CRASH ===\n")
                    append("time=").append(ts).append("\n")
                    append("thread=").append(t.name).append("\n\n")
                    append(e.stackTraceToString())
                    append("\n")
                })
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    fun readAndClear(ctx: Context): String? {
        return try {
            val f = File(ctx.cacheDir, FILE_NAME)
            if (!f.exists()) return null
            val s = f.readText()
            // לא לנקות אם ריק
            if (s.isBlank()) return null
            f.delete()
            s
        } catch (_: Throwable) { null }
    }
}
