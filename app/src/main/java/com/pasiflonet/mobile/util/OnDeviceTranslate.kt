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
