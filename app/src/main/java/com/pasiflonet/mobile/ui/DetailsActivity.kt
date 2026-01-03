package com.pasiflonet.mobile.ui

import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.worker.SendWorker
import androidx.work.workDataOf
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import android.graphics.Bitmap
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.databinding.ActivityDetailsBinding
import com.pasiflonet.mobile.util.OnDeviceTranslate
import com.pasiflonet.mobile.util.Thumbs

class DetailsActivity : AppCompatActivity() {


    private fun readDetailsText(): String {
        val ids = listOf("etCaption", "etMessage", "etText", "inputText")
        for (name in ids) {
            val id = resources.getIdentifier(name, "id", packageName)
            if (id != 0) {
                val v = findViewById<android.widget.TextView>(id)
                return v.text?.toString().orEmpty()
            }
        }
        return ""
    }
    private lateinit var b: ActivityDetailsBinding

    private fun readTargetUsernameFromPrefs(): String {
        // מנסה כמה מפתחות נפוצים כדי לא להיות תלוי ב-AppPrefs שלא יציב כרגע
        val sp = getSharedPreferences("pasiflonet", MODE_PRIVATE)
        val keys = listOf("target_username", "targetUsername", "TARGET_USERNAME", "pref_target_username")
        for (k in keys) {
            val v = sp.getString(k, null)?.trim()
            if (!v.isNullOrBlank()) return v
        }
        // נסה גם prefs בשם אחר
        val sp2 = getSharedPreferences("pasiflonet_prefs", MODE_PRIVATE)
        for (k in keys) {
            val v = sp2.getString(k, null)?.trim()
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }

    private fun readCaptionText(): String {
        // בלי להסתמך על id קבוע (כדי לא לשבור קומפילציה אם השם שונה)
        val ids = listOf("etCaption", "etMessage", "etText", "inputText")
        for (name in ids) {
            val id = resources.getIdentifier(name, "id", packageName)
            if (id != 0) {
                val v = findViewById<android.widget.TextView>(id)
                return v.text?.toString().orEmpty()
            }
        }
        return ""
    }
    private var blurMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)

        // meta/text מגיעים מה-Intent (מהטבלה)
        val meta = intent.getStringExtra("meta") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        val miniThumbB64 = intent.getStringExtra("miniThumbB64")

        b.tvMeta.text = meta
        b.etCaption.setText(text)

        // Preview: גם אם הקובץ עדיין לא ירד - מציגים miniThumb
        val bmp: Bitmap? = Thumbs.b64ToBitmap(miniThumbB64)
        if (bmp != null) b.imgPreview.setImageBitmap(bmp)

        // Blur button: מצב ציור מלבנים
        b.btnBlur.setOnClickListener {
            blurMode = !blurMode
            b.overlay.enabledDraw = blurMode
            val msg = if (blurMode) "✅ מצב טשטוש פעיל: גרור מלבנים על ה-Preview" else "✅ מצב טשטוש כבוי"
            Snackbar.make(b.root, msg, Snackbar.LENGTH_SHORT).show()
        }

        // Translate (חינם on-device)
        b.btnTranslate.setOnClickListener {
            val src = b.etCaption.text?.toString().orEmpty()
            Snackbar.make(b.root, "⏳ מתרגם… (on-device)", Snackbar.LENGTH_SHORT).show()
            OnDeviceTranslate.toHebrew(src) { out, err ->
                runOnUiThread {
                    if (out != null) {
                        b.etCaption.setText(out)
                        Snackbar.make(b.root, "✅ תורגם לעברית", Snackbar.LENGTH_SHORT).show()
                    } else {
                        Snackbar.make(b.root, "❌ תרגום נכשל: ${err ?: "unknown"}", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Watermark: כרגע רק UI (הפעלת editor בהמשך ב-ffmpeg). בינתיים מראה שנקלט.
        b.btnWatermark.setOnClickListener {
            Snackbar.make(b.root, "✅ לוגו/סימן מים: בשלב הבא נצרוב למדיה לפני שליחה", Snackbar.LENGTH_SHORT).show()
        }

        // Send: בשלב הזה רק מאשר UI (השליחה האמיתית + ffmpeg נכניס בפקודה הבאה כדי לא לשבור קומפילציה)
        b.btnSend.setOnClickListener {
            val targetUsername = AppPrefs.getTargetUsername(this).trim()
            if (targetUsername.isBlank()) {
                Snackbar.make(b.root, "❌ חסר ערוץ יעד (@username) במסך ההתחברות", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val srcChatId = intent.getLongExtra("src_chat_id", 0L)
            val srcMsgId = intent.getLongExtra("src_message_id", 0L)

            if (srcChatId == 0L || srcMsgId == 0L) {
                Snackbar.make(b.root, "❌ חסרים src_chat_id / src_message_id", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val text = readDetailsText()

            // תמיד שולחים דרך Worker (שם נקבע האם לשלוח מדיה/או רק טקסט)
            val data = workDataOf(
                SendWorker.KEY_SRC_CHAT_ID to srcChatId,
                SendWorker.KEY_SRC_MESSAGE_ID to srcMsgId,
                SendWorker.KEY_TARGET_USERNAME to targetUsername,
                SendWorker.KEY_TEXT to text,
                SendWorker.KEY_SEND_WITH_MEDIA to true
            )

            val req = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(data)
                .addTag("send_worker")
                .build()

            WorkManager.getInstance(applicationContext).enqueue(req)
            Snackbar.make(b.root, "✅ נשלח לתור שליחה…", Snackbar.LENGTH_SHORT).show()
        }

            val srcChatId = intent.getLongExtra("src_chat_id", 0L)
            val srcMsgId = intent.getLongExtra("src_message_id", 0L)

            if (srcChatId == 0L || srcMsgId == 0L) {
                Snackbar.make(b.root, "❌ חסרים פרטי הודעה (chatId/messageId)", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val text = readCaptionText()

            // כרגע תמיד ננסה לשלוח עם מדיה (אם קיימת בהודעה המקורית), ואם אין – ה-Worker עושה fallback לטקסט בלבד
            val data = workDataOf(
                SendWorker.KEY_SRC_CHAT_ID to srcChatId,
                SendWorker.KEY_SRC_MESSAGE_ID to srcMsgId,
                SendWorker.KEY_TARGET_USERNAME to targetUsername,
                SendWorker.KEY_TEXT to text,
                SendWorker.KEY_SEND_WITH_MEDIA to true
            )

            val req = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(data)
                .addTag("send_worker")
                .build()

            WorkManager.getInstance(applicationContext).enqueue(req)
            Snackbar.make(b.root, "✅ נשלח לתור שליחה…", Snackbar.LENGTH_SHORT).show()
        }
    }
}
