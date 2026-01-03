package com.pasiflonet.mobile.util

import android.util.Base64
import org.drinkless.tdlib.TdApi

object TdExtract {
    fun miniThumbB64(mini: TdApi.Minithumbnail?): String? {
        val b = mini?.data ?: return null
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }
}
