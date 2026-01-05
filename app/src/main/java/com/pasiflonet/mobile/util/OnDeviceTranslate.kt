package com.pasiflonet.mobile.util

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object OnDeviceTranslate {

    private const val TAG = "OnDeviceTranslate"

    private fun detectSourceLangBlocking(text: String, timeoutMs: Long): String? {
        val latch = CountDownLatch(1)
        val out = AtomicReference<String?>(null)
        try {
            LanguageIdentification.getClient()
                .identifyLanguage(text)
                .addOnSuccessListener { lang ->
                    // lang is BCP-47 (e.g. "en", "ru") or "und"
                    out.set(lang)
                    latch.countDown()
                }
                .addOnFailureListener {
                    latch.countDown()
                }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
        val lang = out.get()
        if (lang.isNullOrBlank() || lang == "und") return null
        return lang
    }

    private fun buildTranslator(srcTag: String): Translator? {
        val src = TranslateLanguage.fromLanguageTag(srcTag) ?: return null
        val opts = TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(TranslateLanguage.HEBREW)
            .build()
        return Translation.getClient(opts)
    }

    /**
     * Blocking translation (DO NOT call from UI thread).
     * Downloads model if needed (free, on-device).
     */
    fun translateToHebrewBlocking(ctx: Context, text: String, timeoutMs: Long = 20000L): String? {
        if (text.isBlank()) return ""

        val srcTag = detectSourceLangBlocking(text, timeoutMs) ?: "en"
        val translator = buildTranslator(srcTag) ?: buildTranslator("en") ?: return null

        val latch = CountDownLatch(1)
        val out = AtomicReference<String?>(null)

        try {
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    translator.translate(text)
                        .addOnSuccessListener { t ->
                            out.set(t)
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

            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            Log.w(TAG, "translate crash: ${t.message}")
        } finally {
            try { translator.close() } catch (_: Throwable) {}
        }

        return out.get()
    }
}
