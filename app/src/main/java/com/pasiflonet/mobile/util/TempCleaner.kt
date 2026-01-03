package com.pasiflonet.mobile.util

import android.content.Context
import java.io.File

object TempCleaner {

    fun clean(context: Context): Int {
        var deleted = 0

        deleted += deleteDirContents(context.cacheDir)
        context.externalCacheDir?.let { deleted += deleteDirContents(it) }

        val appTmp = File(context.filesDir, "pasiflonet_tmp")
        if (appTmp.exists()) deleted += deleteDirContents(appTmp)

        // לא נוגעים ב-filesDir/tdlib ולא ב-filesDir/tdfiles כדי לשמור התחברות
        return deleted
    }

    private fun deleteDirContents(dir: File): Int {
        var count = 0
        if (!dir.exists()) return 0
        dir.listFiles()?.forEach { f ->
            count += if (f.isDirectory) {
                val inner = deleteDirContents(f)
                if (f.delete()) inner + 1 else inner
            } else {
                if (f.delete()) 1 else 0
            }
        }
        return count
    }
}
