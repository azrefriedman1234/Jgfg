package com.pasiflonet.mobile.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

object Thumbs {
    fun b64ToBitmap(b64: String?): Bitmap? {
        if (b64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) { null }
    }
}
