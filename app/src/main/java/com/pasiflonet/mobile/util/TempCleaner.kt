package com.pasiflonet.mobile.util

import android.content.Context
import java.io.File

object TempCleaner {

    private fun deleteRec(f: File): Int {
        var n = 0
        if (!f.exists()) return 0
        if (f.isDirectory) f.listFiles()?.forEach { n += deleteRec(it) }
        if (f.delete()) n++
        return n
    }

    fun clean(ctx: Context): Int {
        var n = 0
        // cache
        n += deleteRec(ctx.cacheDir)
        // files/tmp
        val tmp = File(ctx.filesDir, "tmp")
        n += deleteRec(tmp)
        tmp.mkdirs()
        return n
    }
}
