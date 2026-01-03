package com.pasiflonet.mobile.util

import android.util.Base64
import org.drinkless.tdlib.TdApi

object TdThumb {

    fun miniThumbB64(msg: TdApi.Message): String? {
        val c = msg.content ?: return null

        val bytes: ByteArray? = when (c) {
            is TdApi.MessagePhoto -> c.photo?.minithumbnail?.data
            is TdApi.MessageVideo -> c.video?.minithumbnail?.data
            is TdApi.MessageAnimation -> c.animation?.minithumbnail?.data
            is TdApi.MessageDocument -> c.document?.minithumbnail?.data
            is TdApi.MessageSticker -> c.sticker?.minithumbnail?.data
            else -> null
        }

        if (bytes == null || bytes.isEmpty()) return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
