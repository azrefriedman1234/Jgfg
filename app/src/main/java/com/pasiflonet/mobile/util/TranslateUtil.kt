package com.pasiflonet.mobile.util

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TranslateUtil {

    /**
     * תרגום אונליין ללא מודל וללא API Key (endpoint לא רשמי).
     * אם Google יחסום ברגע נתון – הפונקציה תחזיר null במקום להפיל את האפליקציה.
     */
    fun translateToHebrewOnline(text: String): String? {
        val src = text.trim()
        if (src.isBlank()) return ""

        return try {
            val q = URLEncoder.encode(src, "UTF-8")
            val u = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=iw&dt=t&q=$q")
            val conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 12000
                readTimeout = 12000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            conn.disconnect()

            // פורמט: [[["שלום", "hello", ...], ...], ...]
            val arr = JSONArray(body)
            val parts = arr.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val seg = parts.getJSONArray(i)
                sb.append(seg.getString(0))
            }
            sb.toString()
        } catch (_: Throwable) {
            null
        }
    }
}
