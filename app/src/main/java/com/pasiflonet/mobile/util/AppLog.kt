package com.pasiflonet.mobile.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    private const val TAG = "Pasiflonet"
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun logFile(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null), "pasiflonet_logs")
        dir.mkdirs()
        return File(dir, "app.log")
    }

    private fun write(ctx: Context, level: String, msg: String, tr: Throwable? = null) {
        val line = "${df.format(Date())} [$level] $msg" + (tr?.let { "\n" + Log.getStackTraceString(it) } ?: "")
        runCatching {
            logFile(ctx).appendText(line + "\n")
        }
    }

    fun i(ctx: Context, msg: String) { Log.i(TAG, msg); write(ctx, "I", msg) }
    fun w(ctx: Context, msg: String) { Log.w(TAG, msg); write(ctx, "W", msg) }
    fun e(ctx: Context, msg: String, tr: Throwable? = null) { Log.e(TAG, msg, tr); write(ctx, "E", msg, tr) }
}
