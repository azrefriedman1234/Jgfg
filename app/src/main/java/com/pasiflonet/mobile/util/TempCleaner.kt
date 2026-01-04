package com.pasiflonet.mobile.util

import android.content.Context
import java.io.File
import kotlin.math.roundToLong

object TempCleaner {

    data class Result(val deletedFiles: Int, val freedBytes: Long)

    fun tempDir(ctx: Context): File {
        val d = File(ctx.cacheDir, "pasiflonet_tmp")
        d.mkdirs()
        return d
    }

    fun clean(ctx: Context): Result {
        val d = tempDir(ctx)
        if (!d.exists()) return Result(0, 0)

        var count = 0
        var bytes = 0L

        d.listFiles()?.forEach { f ->
            if (f.isFile) {
                bytes += f.length()
                if (f.delete()) count++
            } else if (f.isDirectory) {
                f.deleteRecursively()
                count++
            }
        }

        return Result(count, bytes)
    }

    fun fmt(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> "${(mb * 10).roundToLong() / 10.0} MB"
            kb >= 1 -> "${(kb * 10).roundToLong() / 10.0} KB"
            else -> "$bytes B"
        }
    }
}
