package com.pasiflonet.mobile.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.databinding.ActivityDetailsBinding
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.util.OnDeviceTranslate
import com.pasiflonet.mobile.util.Thumbs
import org.drinkless.tdlib.TdApi

class DetailsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDetailsBinding

    private val pickLogo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        AppPrefs.setWatermark(this, uri.toString())
        Snackbar.make(b.root, "✅ לוגו נשמר", Snackbar.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(b.root)

        val chatId = intent.getLongExtra("chatId", 0L)
        val msgId = intent.getLongExtra("msgId", 0L)
        val dateUnix = intent.getIntExtra("dateUnix", 0)
        val type = intent.getStringExtra("type") ?: ""
        val text = intent.getStringExtra("text") ?: ""
        val miniThumbB64 = intent.getStringExtra("miniThumbB64")
        val localPath = intent.getStringExtra("localPath")

        b.etCaption.setText(text)
        b.tvMeta.text = "chatId=$chatId | msgId=$msgId | type=$type | date=$dateUnix\nlocalPath=${localPath ?: "-"}"

        // Preview מלמעלה למטה בצד שמאל (minithumbnail)
        val bmp = Thumbs.b64ToBitmap(miniThumbB64)
        if (bmp != null) b.imgPreview.setImageBitmap(bmp)

        // טשטוש: מוסיף ריבוע, אפשר לגרור
        b.btnBlur.setOnClickListener {
            b.overlayEditor.addRectCentered()
            Snackbar.make(b.root, "✅ הוסף אזור טשטוש (אפשר לגרור)", Snackbar.LENGTH_SHORT).show()
        }

        // לוגו/סימן מים: כרגע “בחירה ושמירה” (אח״כ ניישם צריבה לוידאו/תמונה)
        b.btnWatermark.setOnClickListener {
            pickLogo.launch(arrayOf("image/*"))
        }

        // תרגום חינם on-device
        b.btnTranslate.setOnClickListener {
            val src = b.etCaption.text?.toString() ?: ""
            Snackbar.make(b.root, "⏳ מתרגם…", Snackbar.LENGTH_SHORT).show()
            OnDeviceTranslate.toHebrew(src) { out, err ->
                runOnUiThread {
                    if (out != null) {
                        b.etCaption.setText(out)
                        Snackbar.make(b.root, "✅ תורגם", Snackbar.LENGTH_SHORT).show()
                    } else {
                        Snackbar.make(b.root, "❌ שגיאת תרגום: ${err ?: "unknown"}", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        // שליחה אמיתית: resolve @username -> chat.id ואז SendMessage
        b.btnSend.setOnClickListener {
            val target = AppPrefs.getTargetUsername(this).trim()
            val caption = b.etCaption.text?.toString()?.trim().orEmpty()

            if (target.isBlank() || !target.startsWith("@")) {
                Snackbar.make(b.root, "❌ צריך להגדיר ערוץ יעד בפורמט @username", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val username = target.removePrefix("@")

            TdLibManager.send(TdApi.SearchPublicChat(username)) { obj ->
                if (obj is TdApi.Error) {
                    runOnUiThread {
                        Snackbar.make(b.root, "❌ לא נמצא ערוץ: ${obj.message}", Snackbar.LENGTH_LONG).show()
                    }
                    return@send
                }

                val chat = obj as TdApi.Chat
                val input: TdApi.InputMessageContent =
                    if (!localPath.isNullOrBlank()) {
                        // טקסט+מדיה (כ-Document הכי יציב)
                        val file = TdApi.InputFileLocal(localPath)
                        val ft = TdApi.FormattedText(caption, null)
                        TdApi.InputMessageDocument(file, null, false, ft)
                    } else {
                        // רק טקסט
                        val ft = TdApi.FormattedText(caption, null)
                        TdApi.InputMessageText(ft, null, false)
                    }

                TdLibManager.send(TdApi.SendMessage(chat.id, null, null, null, null, input)) { res ->
                    runOnUiThread {
                        if (res is TdApi.Error) {
                            Snackbar.make(b.root, "❌ שליחה נכשלה: ${res.message}", Snackbar.LENGTH_LONG).show()
                        } else {
                            Snackbar.make(b.root, "✅ נשלח באמת לערוץ ${target}", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}
