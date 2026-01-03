package com.pasiflonet.mobile.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.databinding.ActivityDetailsBinding
import com.pasiflonet.mobile.model.MessageRow
import com.pasiflonet.mobile.ui.overlay.EditOverlayView
import com.pasiflonet.mobile.util.JsonUtil
import com.pasiflonet.mobile.worker.SendWorker
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROW_JSON = "row_json"
    }

    private lateinit var b: ActivityDetailsBinding
    private lateinit var prefs: AppPrefs
    private lateinit var row: MessageRow

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = AppPrefs(this)

        val rowJson = intent.getStringExtra(EXTRA_ROW_JSON) ?: run {
            finish()
            return
        }
        row = JsonUtil.fromJson(rowJson)

        b.etText.setText(row.text)

        if (row.thumbLocalPath != null) {
            b.ivMedia.setImageURI(Uri.fromFile(java.io.File(row.thumbLocalPath)))
        } else {
            b.mediaFrame.visibility = View.GONE
        }

        b.btnWatermark.setOnClickListener {
            b.overlay.setMode(EditOverlayView.Mode.WATERMARK)
            Snackbar.make(b.root, "גע בתמונה כדי למקם סימן מים", Snackbar.LENGTH_SHORT).show()
        }

        b.btnBlur.setOnClickListener {
            b.overlay.setMode(EditOverlayView.Mode.BLUR)
            Snackbar.make(b.root, "גרור על התמונה כדי לסמן אזורי טשטוש", Snackbar.LENGTH_SHORT).show()
        }

        // Auto translate if not Hebrew
        autoTranslateIfNeeded(row.text)

        b.btnSend.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val targetChatId = prefs.targetChatIdFlow.first()
                if (targetChatId == 0L) {
                    runOnUiThread {
                        Snackbar.make(b.root, "חובה להגדיר ערוץ יעד במסך ההתחברות/הגדרות", Snackbar.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val wmUri = prefs.watermarkUriFlow.first()
                val sendWithMedia = b.rbWithMedia.isChecked
                val planJson = JsonUtil.toJson(b.overlay.getPlan())

                val data = Data.Builder()
                    .putLong(SendWorker.KEY_SRC_CHAT_ID, row.chatId)
                    .putLong(SendWorker.KEY_SRC_MESSAGE_ID, row.messageId)
                    .putLong(SendWorker.KEY_TARGET_CHAT_ID, targetChatId)
                    .putString(SendWorker.KEY_TEXT, b.etText.text?.toString().orEmpty())
                    .putString(SendWorker.KEY_TRANSLATION, b.etTranslation.text?.toString().orEmpty())
                    .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, sendWithMedia)
                    .putString(SendWorker.KEY_WATERMARK_URI, wmUri)
                    .putString(SendWorker.KEY_EDIT_PLAN_JSON, planJson)
                    .build()

                val req = OneTimeWorkRequestBuilder<SendWorker>()
                    .setInputData(data)
                    .build()

                WorkManager.getInstance(this@DetailsActivity).enqueue(req)

                runOnUiThread {
                    Snackbar.make(b.root, "נשלח לרקע…", Snackbar.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun autoTranslateIfNeeded(text: String) {
        if (text.isBlank()) return
        val idClient = LanguageIdentification.getClient()
        idClient.identifyLanguage(text)
            .addOnSuccessListener { lang ->
                // ML Kit returns "und" if unknown.
                if (lang == "he" || lang == "iw" || lang == "und") return@addOnSuccessListener
                val src = TranslateLanguage.fromLanguageTag(lang) ?: return@addOnSuccessListener
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(TranslateLanguage.HEBREW)
                    .build()
                val translator = Translation.getClient(options)
                translator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        translator.translate(text)
                            .addOnSuccessListener { translated ->
                                b.etTranslation.setText(translated)
                            }
                            .addOnFailureListener { /* ignore */ }
                    }
                    .addOnFailureListener { /* ignore */ }
            }
            .addOnFailureListener { /* ignore */ }
    }
}
