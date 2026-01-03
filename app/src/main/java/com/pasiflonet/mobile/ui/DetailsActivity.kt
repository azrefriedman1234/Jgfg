package com.pasiflonet.mobile.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.worker.SendWorker

class DetailsActivity : AppCompatActivity() {

    companion object {
        private const val EX_CHAT_ID = "chat_id"
        private const val EX_MSG_ID = "msg_id"
        private const val EX_TEXT = "text"

        fun start(ctx: Context, chatId: Long, msgId: Long, text: String) {
            val i = Intent(ctx, DetailsActivity::class.java)
            i.putExtra(EX_CHAT_ID, chatId)
            i.putExtra(EX_MSG_ID, msgId)
            i.putExtra(EX_TEXT, text)
            ctx.startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        val imgPreview = findViewById<ImageView>(R.id.imgPreview)
        val tvMeta = findViewById<TextView>(R.id.tvMeta)
        val etText = findViewById<EditText>(R.id.etText)

        val btnWatermark = findViewById<Button>(R.id.btnWatermark)
        val btnBlur = findViewById<Button>(R.id.btnBlur)
        val btnTranslate = findViewById<Button>(R.id.btnTranslate)
        val btnSend = findViewById<Button>(R.id.btnSend)

        val chatId = intent.getLongExtra(EX_CHAT_ID, 0L)
        val msgId = intent.getLongExtra(EX_MSG_ID, 0L)
        val initialText = intent.getStringExtra(EX_TEXT).orEmpty()

        tvMeta.text = "chatId=$chatId  msgId=$msgId"
        etText.setText(initialText)

        // כרגע placeholder (עד שנוסיף thumb אמיתי למעלה)
        imgPreview.setImageDrawable(null)

        btnWatermark.setOnClickListener {
            Snackbar.make(btnSend, "בקרוב: עורך לוגו/סימן מים", Snackbar.LENGTH_SHORT).show()
        }

        btnBlur.setOnClickListener {
            Snackbar.make(btnSend, "בקרוב: טשטוש אזורים ידני", Snackbar.LENGTH_SHORT).show()
        }

        btnTranslate.setOnClickListener {
            Snackbar.make(btnSend, "בקרוב: תרגום אוטומטי", Snackbar.LENGTH_SHORT).show()
        }

        btnSend.setOnClickListener {
            val target = AppPrefs.getTargetUsername(this).trim()
            val textToSend = etText.text?.toString().orEmpty()

            if (target.isBlank()) {
                Snackbar.make(btnSend, "חסר ערוץ יעד @username בהתחברות", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (textToSend.isBlank()) {
                Snackbar.make(btnSend, "אין טקסט לשליחה", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val data = Data.Builder()
                .putLong(SendWorker.KEY_SRC_CHAT_ID, chatId)
                .putLong(SendWorker.KEY_SRC_MESSAGE_ID, msgId)
                .putString(SendWorker.KEY_TARGET_USERNAME, target)
                .putString(SendWorker.KEY_TEXT, textToSend)
                .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, false)
                .build()

            val req = OneTimeWorkRequestBuilder<SendWorker>()
                .setInputData(data)
                .build()

            WorkManager.getInstance(applicationContext).enqueue(req)
            Snackbar.make(btnSend, "✅ נשלח לתור שליחה (WorkManager). תבדוק בערוץ יעד.", Snackbar.LENGTH_LONG).show()
        }
    }
}
