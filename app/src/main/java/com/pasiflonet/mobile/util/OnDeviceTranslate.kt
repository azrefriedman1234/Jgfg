package com.pasiflonet.mobile.util

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator

object OnDeviceTranslate {

    private fun translator(): Translator {
        val opts = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.HEBREW)
            .build()
        return Translation.getClient(opts)
    }

    fun toHebrew(text: String, cb: (out: String?, err: String?) -> Unit) {
        if (text.isBlank()) return cb("", null)

        val t = translator()
        val cond = DownloadConditions.Builder().build()
        t.downloadModelIfNeeded(cond)
            .addOnSuccessListener {
                t.translate(text)
                    .addOnSuccessListener { out -> cb(out, null); t.close() }
                    .addOnFailureListener { e -> cb(null, e.message); t.close() }
            }
            .addOnFailureListener { e -> cb(null, e.message); t.close() }
    }
}

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object OnDeviceTranslate {
    private fun translator(): com.google.mlkit.nl.translate.Translator {
        val opts = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
            .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
            .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.HEBREW)
            .build()
        return com.google.mlkit.nl.translate.Translation.getClient(opts)
    }

    fun translateToHebrewBlocking(ctx: android.content.Context, text: String, timeoutMs: Long = 20000L): String? {
        val t = translator()
        val latch = CountDownLatch(1)
        val out = arrayOfNulls<String>(1)
        val err = arrayOfNulls<Throwable>(1)

        val cond = com.google.mlkit.common.model.DownloadConditions.Builder().build()
        t.downloadModelIfNeeded(cond)
            .continueWithTask { t.translate(text) }
            .addOnSuccessListener { res -> out[0] = res; latch.countDown() }
            .addOnFailureListener { e -> err[0] = e; latch.countDown() }

        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        try { t.close() } catch (_: Throwable) {}

        if (err[0] != null) throw err[0]!!
        return out[0]
    }
}
