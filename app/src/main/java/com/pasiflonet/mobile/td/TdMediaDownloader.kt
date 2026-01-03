package com.pasiflonet.mobile.td

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.td.libcore.telegram.TdApi
import kotlin.coroutines.resume

object TdMediaDownloader {

    private const val TAG = "TdMediaDownloader"

    suspend fun downloadFile(fileId: Int, priority: Int = 32, synchronous: Boolean = true): String? {
        return suspendCancellableCoroutine { cont ->
            TdLibManager.send(TdApi.DownloadFile(fileId, priority, 0, 0, synchronous)) { obj ->
                when (obj.constructor) {
                    TdApi.File.CONSTRUCTOR -> {
                        val f = obj as TdApi.File
                        val path = f.local?.path
                        Log.i(TAG, "Downloaded fileId=$fileId -> $path")
                        cont.resume(path)
                    }
                    TdApi.Error.CONSTRUCTOR -> {
                        val e = obj as TdApi.Error
                        Log.e(TAG, "Download error: ${e.message}")
                        cont.resume(null)
                    }
                    else -> cont.resume(null)
                }
            }
        }
    }

    suspend fun getMessage(chatId: Long, messageId: Long): TdApi.Message? {
        return suspendCancellableCoroutine { cont ->
            TdLibManager.send(TdApi.GetMessage(chatId, messageId)) { obj ->
                when (obj.constructor) {
                    TdApi.Message.CONSTRUCTOR -> cont.resume(obj as TdApi.Message)
                    else -> cont.resume(null)
                }
            }
        }
    }
}
