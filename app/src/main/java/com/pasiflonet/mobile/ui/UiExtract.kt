package com.pasiflonet.mobile.ui

import org.drinkless.tdlib.TdApi

/**
 * מחזיר: (text, hasMedia, miniThumbB64)
 * כדי ש- MainActivity יוכל לסווג הודעות מדיה.
 */
fun extractUiFields(msg: TdApi.Message): Triple<String, Boolean, String?> {
    val c = msg.content
    if (c == null) return Triple("", false, null)

    return when (c) {
        is TdApi.MessageText -> Triple(c.text?.text.orEmpty(), false, null)
        is TdApi.MessagePhoto -> {
            val cap = c.caption?.text.orEmpty()
            val mini = c.photo?.minithumbnail?.data?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
            Triple(cap, true, mini)
        }
        is TdApi.MessageVideo -> {
            val cap = c.caption?.text.orEmpty()
            val mini = c.video?.minithumbnail?.data?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
            Triple(cap, true, mini)
        }
        is TdApi.MessageAnimation -> {
            val cap = c.caption?.text.orEmpty()
            val mini = c.animation?.minithumbnail?.data?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
            Triple(cap, true, mini)
        }
        is TdApi.MessageDocument -> {
            val cap = c.caption?.text.orEmpty()
            val mini = c.document?.minithumbnail?.data?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }
            Triple(cap, true, mini)
        }
        else -> Triple("", true, null)
    }
}
