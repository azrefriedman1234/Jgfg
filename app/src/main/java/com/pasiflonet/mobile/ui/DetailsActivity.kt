package com.pasiflonet.mobile.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.worker.SendWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SRC_CHAT_ID = "src_chat_id"
        const val EXTRA_SRC_MESSAGE_ID = "src_message_id"
        const val EXTRA_TEXT = "text"

        fun start(ctx: Context, chatId: Long, msgId: Long, text: String) {
            val i = Intent(ctx, DetailsActivity::class.java)
            i.putExtra(EXTRA_SRC_CHAT_ID, chatId)
            i.putExtra(EXTRA_SRC_MESSAGE_ID, msgId)
            i.putExtra(EXTRA_TEXT, text)
            ctx.startActivity(i)
        }
    }

    private lateinit var ivPreview: ImageView
    private lateinit var blurOverlay: BlurOverlayView
    private lateinit var tvMeta: TextView
    private lateinit var etCaption: com.google.android.material.textfield.TextInputEditText
    private lateinit var swSendWithMedia: SwitchMaterial

    private var srcChatId: Long = 0L
    private var srcMsgId: Long = 0L

    private var hasMedia: Boolean = false
    private var mediaMime: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        ivPreview = findViewById(R.id.ivPreview)
        blurOverlay = findViewById(R.id.blurOverlay)
        tvMeta = findViewById(R.id.tvMeta)
        etCaption = findViewById(R.id.etCaption)
        swSendWithMedia = findViewById(R.id.swSendWithMedia)

        srcChatId = intent.getLongExtra(EXTRA_SRC_CHAT_ID, 0L)
        srcMsgId = intent.getLongExtra(EXTRA_SRC_MESSAGE_ID, 0L)
        etCaption.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())

        // always visible
        swSendWithMedia.visibility = View.VISIBLE
        swSendWithMedia.isEnabled = false
        swSendWithMedia.isChecked = false

        // ensure overlay is on top
        blurOverlay.visibility = View.VISIBLE
        blurOverlay.bringToFront()
        blurOverlay.invalidate()

        findViewById<View>(R.id.btnBlur).setOnClickListener {
            // best-effort: depending on your BlurOverlayView implementation
            try { blurOverlay.bringToFront() } catch (_: Throwable) {}
            try { blurOverlay.invalidate() } catch (_: Throwable) {}
            Snackbar.make(ivPreview, "גרור מלבן על התמונה לטשטוש (אם תומך).", Snackbar.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnWatermark).setOnClickListener {
            Snackbar.make(ivPreview, "✅ סימן מים מוחל בשליחה (FFmpeg) לפי ההגדרות.", Snackbar.LENGTH_LONG).show()
        }

        findViewById<View>(R.id.btnTranslate).setOnClickListener {
            Snackbar.make(ivPreview, "ℹ️ תרגום כרגע לא נוגע כדי לא לשבור קומפילציה. נטפל אחרי שהבנייה יציבה.", Snackbar.LENGTH_LONG).show()
        }

        findViewById<View>(R.id.btnSend).setOnClickListener { enqueueSendAndReturn() }

        TdLibManager.init(this)
        TdLibManager.ensureClient()

        tvMeta.text = "chatId=$srcChatId | msgId=$srcMsgId\nיעד: ${AppPrefs.getTargetUsername(this).ifBlank { "(לא הוגדר)" }}"

        lifecycleScope.launch(Dispatchers.IO) {
            val msg = fetchMessageSync(srcChatId, srcMsgId)
            if (msg == null) {
                runOnUiThread {
                    ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)
                    Snackbar.make(ivPreview, "❌ לא הצלחתי להביא הודעה מ-Telegram", Snackbar.LENGTH_LONG).show()
                }
                return@launch
            }

            val info = analyzeMessage(msg)
            hasMedia = info.hasMedia
            mediaMime = info.mime

            val bmp = loadBestPreviewBitmap(msg)

            runOnUiThread {
                swSendWithMedia.isEnabled = hasMedia
                swSendWithMedia.isChecked = hasMedia
                if (bmp != null) ivPreview.setImageBitmap(bmp)
                else ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)

                val extra = "\nmedia=" + (if (hasMedia) "YES" else "NO") + (mediaMime?.let { " ($it)" } ?: "")
                tvMeta.text = tvMeta.text.toString() + extra
            }
        }
    }

    private data class MsgInfo(val hasMedia: Boolean, val mime: String?)

    private fun analyzeMessage(m: TdApi.Message): MsgInfo {
        val c = m.content ?: return MsgInfo(false, null)
        return when (c) {
            is TdApi.MessageText -> MsgInfo(false, null)
            is TdApi.MessagePhoto -> MsgInfo(true, "image/jpeg")
            is TdApi.MessageVideo -> MsgInfo(true, c.video?.mimeType)
            is TdApi.MessageAnimation -> MsgInfo(true, c.animation?.mimeType)
            is TdApi.MessageDocument -> MsgInfo(true, c.document?.mimeType)
            else -> MsgInfo(true, null)
        }
    }

    private fun fetchMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        val latch = CountDownLatch(1)
        var out: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) out = obj
            latch.countDown()
        }
        latch.await(20, TimeUnit.SECONDS)
        return out
    }

    private fun loadBestPreviewBitmap(msg: TdApi.Message): Bitmap? {
        val c = msg.content ?: return null

        val thumbFileId: Int? = when (c) {
            is TdApi.MessagePhoto -> {
                val sizes = c.photo?.sizes ?: emptyArray()
                val best = sizes.maxByOrNull { it.width * it.height }
                best?.photo?.id
            }
            is TdApi.MessageVideo -> c.video?.thumbnail?.file?.id
            is TdApi.MessageAnimation -> c.animation?.thumbnail?.file?.id
            is TdApi.MessageDocument -> c.document?.thumbnail?.file?.id
            else -> null
        }

        if (thumbFileId != null) {
            val f = ensureFileDownloaded(thumbFileId, timeoutSec = 45)
            if (f != null && f.exists() && f.length() > 0) {
                return BitmapFactory.decodeFile(f.absolutePath)
            }
        }
        return null
    }

    private fun ensureFileDownloaded(fileId: Int, timeoutSec: Int): File? {
        TdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { }

        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            var f: TdApi.File? = null
            TdLibManager.send(TdApi.GetFile(fileId)) { obj ->
                if (obj is TdApi.File) f = obj
                latch.countDown()
            }
            latch.await(10, TimeUnit.SECONDS)

            val path = f?.local?.path
            val done = f?.local?.isDownloadingCompleted ?: false
            if (!path.isNullOrBlank() && done) {
                val ff = File(path)
                if (ff.exists() && ff.length() > 0) return ff
            }
            Thread.sleep(350)
        }
        return null
    }

    private fun enqueueSendAndReturn() {
        val target = AppPrefs.getTargetUsername(this).trim()
        if (target.isBlank()) {
            Snackbar.make(ivPreview, "❌ לא הוגדר @username יעד", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (srcChatId == 0L || srcMsgId == 0L) {
            Snackbar.make(ivPreview, "❌ חסרים מזהי מקור", Snackbar.LENGTH_SHORT).show()
            return
        }

        val sendWithMedia = swSendWithMedia.isEnabled && swSendWithMedia.isChecked

        val rectsStr = try {
            val rects = blurOverlay.exportRectsNormalized()
            rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
        } catch (_: Throwable) {
            ""
        }

        val data = Data.Builder()
            .putLong(SendWorker.KEY_SRC_CHAT_ID, srcChatId)
            .putLong(SendWorker.KEY_SRC_MESSAGE_ID, srcMsgId)
            .putString(SendWorker.KEY_TARGET_USERNAME, target)
            .putString(SendWorker.KEY_TEXT, etCaption.text?.toString().orEmpty())
            .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, sendWithMedia)
            .putString(SendWorker.KEY_MEDIA_URI, "") // worker fetches from Telegram
            .putString(SendWorker.KEY_MEDIA_MIME, mediaMime.orEmpty())
            .putString(SendWorker.KEY_WATERMARK_URI, AppPrefs.getWatermark(this).trim())
            .putString(SendWorker.KEY_BLUR_RECTS, rectsStr)
            .putFloat(SendWorker.KEY_WM_X, -1f)
            .putFloat(SendWorker.KEY_WM_Y, -1f)
            .build()

        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(req)
        Snackbar.make(ivPreview, "✅ נשלח לתור. חוזר לטבלה…", Snackbar.LENGTH_SHORT).show()
        finish()
    }
}
