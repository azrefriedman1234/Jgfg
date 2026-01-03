package com.pasiflonet.mobile.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFmt {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    fun formatUnixSeconds(sec: Long): String = fmt.format(Date(sec * 1000L))
}
