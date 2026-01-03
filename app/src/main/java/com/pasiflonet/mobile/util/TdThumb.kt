package com.pasiflonet.mobile.util

import android.util.Base64

object TdThumb {

    /**
     * Extracts TDLib minithumbnail.data (byte[]) and returns Base64 string.
     * Uses reflection so it won't break if the generated TDLib model differs slightly.
     */
    fun extractMiniThumbB64(any: Any?): String? {
        if (any == null) return null

        val mt = getAnyField(any, "minithumbnail")
            ?: getAnyField(any, "miniThumbnail")
            ?: getAnyField(any, "minithumb")
            ?: return null

        val data = getAnyField(mt, "data") as? ByteArray ?: return null
        if (data.isEmpty()) return null
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun getAnyField(obj: Any, name: String): Any? {
        // public field
        try {
            val f = obj.javaClass.getField(name)
            f.isAccessible = true
            return f.get(obj)
        } catch (_: Throwable) {}

        // declared field
        try {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            return f.get(obj)
        } catch (_: Throwable) {}

        return null
    }
}
