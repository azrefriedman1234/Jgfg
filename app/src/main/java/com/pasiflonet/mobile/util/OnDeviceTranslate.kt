package com.pasiflonet.mobile.util

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object OnDeviceTranslate {
    private const val TAG = "OnDeviceTranslate"

    private fun translator(): Translator {
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH) // MLKit דורש שפה מקור — נשאיר English כברירת מחדל
            .setTargetLanguage(TranslateLanguage.HEBREW)
            .build()
        return Translation.getClient(opts)
    }

    /**
     * תרגום אופליין (ML Kit). אם חסר מודל — יוריד אותו (חינם).
     * מחזיר null אם נכשל/טיים-אאוט.
     */
    fun translateToHebrewBlocking(ctx: Context, text: String, timeoutMs: Long = 20000L): String? {
        if (text.isBlank()) return ""

        val t = translator()
        val out = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        val conditions = DownloadConditions.Builder().build()

        t.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                t.translate(text)
                    .addOnSuccessListener { res ->
                        out.set(res)
                        latch.countDown()
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "translate failed: ${e.message}")
                        latch.countDown()
                    }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "model download failed: ${e.message}")
                latch.countDown()
            }

        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {}

        try { t.close() } catch (_: Throwable) {}
        return out.get()
    }
}

/** Wrapper top-level כדי שתוכל לקרוא לזה בקלות מכל מקום */
fun translateToHebrewBlocking(ctx: Context, text: String, timeoutMs: Long = 20000L): String? {
    return OnDeviceTranslate.translateToHebrewBlocking(ctx, text, timeoutMs)
}
