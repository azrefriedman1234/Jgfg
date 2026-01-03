package com.pasiflonet.mobile.ui.adapters

data class MessageRow(
    val msgId: Long,
    val chatId: Long,
    val dateUnix: Int,
    val text: String,
    val type: String,
    val miniThumbB64: String? = null,
    val localPath: String? = null
)
