package com.pasiflonet.mobile.util

import android.content.Context
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

object TranslateUtil {

    /**
     * Translate any text to Hebrew (on-device, free).
     * Flow:
     * 1) Identify language
     * 2) If already Hebrew -> return original
     * 3) Create translator src->he and download model if needed
     */
    fun toHebrew(
        ctx: Context,
        text: String,
        onResult: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val t = text.trim()
        if (t.isBlank()) {
            onResult(text)
            return
        }

        val identifier = LanguageIdentification.getClient()
        identifier.identifyLanguage(t)
            .addOnSuccessListener { lang ->
                if (lang == "he" || lang == "iw") {
                    onResult(text)
                    return@addOnSuccessListener
                }

                val src = if (lang == "und" || lang.isBlank()) TranslateLanguage.ENGLISH else lang
                val srcCode = TranslateLanguage.fromLanguageTag(src) ?: TranslateLanguage.ENGLISH
                val opts = TranslatorOptions.Builder()
                    .setSourceLanguage(srcCode)
                    .setTargetLanguage(TranslateLanguage.HEBREW)
                    .build()

                val translator: Translator = Translation.getClient(opts)
                translator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        translator.translate(text)
                            .addOnSuccessListener { out ->
                                translator.close()
                                onResult(out)
                            }
                            .addOnFailureListener { e ->
                                translator.close()
                                onError(e)
                            }
                    }
                    .addOnFailureListener { e ->
                        translator.close()
                        onError(e)
                    }
            }
            .addOnFailureListener { e ->
                onError(e)
            }
    }
}
