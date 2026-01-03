package com.pasiflonet.mobile.util

import org.drinkless.tdlib.TdApi

object TdExtract {

    fun textAndType(m: TdApi.Message): Pair<String, String> {
        val c = m.content ?: return "" to "OTHER"
        return when (c) {
            is TdApi.MessageText -> (c.text?.text ?: "") to "TEXT"
            is TdApi.MessagePhoto -> ((c.caption?.text ?: "")).ifBlank { "Photo" } to "PHOTO"
            is TdApi.MessageVideo -> ((c.caption?.text ?: "")).ifBlank { "Video" } to "VIDEO"
            is TdApi.MessageDocument -> ((c.caption?.text ?: "")).ifBlank { "Document" } to "DOC"
            else -> c.javaClass.simpleName to "OTHER"
        }
    }

    fun miniThumbB64(m: TdApi.Message): String? {
        val c = m.content ?: return null
        val mini = when (c) {
            is TdApi.MessagePhoto -> c.photo?.minithumbnail
            is TdApi.MessageVideo -> c.video?.minithumbnail
            is TdApi.MessageDocument -> c.document?.minithumbnail
            else -> null
        } ?: return null
        return mini.data
    }

    fun localPathIfAny(m: TdApi.Message): String? {
        val c = m.content ?: return null
        val file = when (c) {
            is TdApi.MessagePhoto -> c.photo?.sizes?.lastOrNull()?.photo
            is TdApi.MessageVideo -> c.video?.video
            is TdApi.MessageDocument -> c.document?.document
            else -> null
        } ?: return null

        val lp = file.local?.path
        return if (!lp.isNullOrBlank()) lp else null
    }
}
