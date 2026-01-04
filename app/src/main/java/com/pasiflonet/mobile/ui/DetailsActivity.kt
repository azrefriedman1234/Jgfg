package com.pasiflonet.mobile.ui

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.MotionEvent
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
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.td.TdLibManager
import com.pasiflonet.mobile.worker.SendWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class DetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SRC_CHAT_ID = "src_chat_id"
        const val EXTRA_SRC_MESSAGE_ID = "src_message_id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_MEDIA_URI = "media_uri"
        const val EXTRA_MEDIA_MIME = "media_mime"
        const val EXTRA_MINITHUMB_B64 = "mini_thumb_b64"
        const val EXTRA_HAS_MEDIA_HINT = "has_media_hint"

        fun start(
            ctx: Context,
            chatId: Long,
            msgId: Long,
            text: String,
            mediaUri: String? = null,
            mediaMime: String? = null,
            miniThumbB64: String? = null,
            hasMediaHint: Boolean = false
        ) {
            val i = Intent(ctx, DetailsActivity::class.java)
            i.putExtra(EXTRA_SRC_CHAT_ID, chatId)
            i.putExtra(EXTRA_SRC_MESSAGE_ID, msgId)
            i.putExtra(EXTRA_TEXT, text)
            if (!mediaUri.isNullOrBlank()) i.putExtra(EXTRA_MEDIA_URI, mediaUri)
            if (!mediaMime.isNullOrBlank()) i.putExtra(EXTRA_MEDIA_MIME, mediaMime)
            if (!miniThumbB64.isNullOrBlank()) i.putExtra(EXTRA_MINITHUMB_B64, miniThumbB64)
            i.putExtra(EXTRA_HAS_MEDIA_HINT, hasMediaHint)
            ctx.startActivity(i)
        }
    }

    private lateinit var ivPreview: ImageView
    private lateinit var ivWatermarkOverlay: ImageView
    private lateinit var blurOverlay: BlurOverlayView
    private lateinit var tvMeta: TextView
    private lateinit var etCaption: com.google.android.material.textfield.TextInputEditText
    private lateinit var swSendWithMedia: SwitchMaterial

    private var srcChatId: Long = 0L
    private var srcMsgId: Long = 0L

    private var mediaUri: Uri? = null
    private var mediaMime: String? = null
    private var miniThumbB64: String? = null
    private var hasMediaHint: Boolean = false

    private var wmDragging = false
    private var wmDx = 0f
    private var wmDy = 0f

    private val langId by lazy { LanguageIdentification.getClient() }
    private var translator: Translator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        TdLibManager.init(this)
        TdLibManager.ensureClient()

        ivPreview = findViewById(R.id.ivPreview)
        ivWatermarkOverlay = findViewById(R.id.ivWatermarkOverlay)
        blurOverlay = findViewById(R.id.blurOverlay)
        tvMeta = findViewById(R.id.tvMeta)
        etCaption = findViewById(R.id.etCaption)
        swSendWithMedia = findViewById(R.id.swSendWithMedia)

        srcChatId = intent.getLongExtra(EXTRA_SRC_CHAT_ID, 0L)
        srcMsgId = intent.getLongExtra(EXTRA_SRC_MESSAGE_ID, 0L)
        mediaMime = intent.getStringExtra(EXTRA_MEDIA_MIME)
        miniThumbB64 = intent.getStringExtra(EXTRA_MINITHUMB_B64)
        hasMediaHint = intent.getBooleanExtra(EXTRA_HAS_MEDIA_HINT, false)
        mediaUri = intent.getStringExtra(EXTRA_MEDIA_URI)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

        etCaption.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())
        tvMeta.text = "chatId=$srcChatId | msgId=$srcMsgId"

        setupWatermarkOverlayDrag()
        showWatermarkOverlayIfConfigured()

        val hasMedia = hasMediaHint || (mediaUri != null) || !mediaMime.isNullOrBlank() || !miniThumbB64.isNullOrBlank()
        swSendWithMedia.isEnabled = hasMedia
        swSendWithMedia.isChecked = hasMedia
        swSendWithMedia.text = if (hasMedia) "שלח עם מדיה (אם קיימת)" else "שלח עם מדיה (אין מדיה בהודעה)"

        loadPreview()
        fetchSharpPreviewFromTelegram()

        findViewById<View>(R.id.btnWatermark).setOnClickListener {
            showWatermarkOverlayIfConfigured(forceShow = true)
            Snackbar.make(ivPreview, "גרור את סימן המים למיקום הרצוי", Snackbar.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnBlur).setOnClickListener { toggleBlurMode() }
        findViewById<View>(R.id.btnTranslate).setOnClickListener { translateToHebrew() }
        findViewById<View>(R.id.btnSend).setOnClickListener { enqueueSendAndReturn() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { langId.close() }
        runCatching { translator?.close() }
    }

    private fun loadPreview() {
        // 1) local mediaUri (אם יש)
        mediaUri?.let { uri ->
            val bmp = readBitmap(uri)
            if (bmp != null) {
                ivPreview.setImageBitmap(bmp)
                return
            }
            val frame = readVideoFrame(uri)
            if (frame != null) {
                ivPreview.setImageBitmap(frame)
                return
            }
        }

        // 2) miniThumb fallback
        val mt = decodeMiniThumb(miniThumbB64)
        if (mt != null) {
            ivPreview.setImageBitmap(mt)
        } else {
            ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)
        }
    }

    private fun decodeMiniThumb(b64: String?): Bitmap? {
        if (b64.isNullOrBlank()) return null
        return try {
            val raw = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: run {
                // headerless jpeg → prepend jfif
                val jfif = byteArrayOf(
                    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
                    0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
                    0x00, 0x01, 0x00, 0x01, 0x00, 0x00
                )
                val full = jfif + raw
                BitmapFactory.decodeByteArray(full, 0, full.size)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readBitmap(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                BitmapFactory.decodeStream(input)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readVideoFrame(uri: Uri): Bitmap? {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(this, uri)
            val b = r.getFrameAtTime(0)
            r.release()
            b
        } catch (_: Throwable) {
            null
        }
    }

    private fun showWatermarkOverlayIfConfigured(forceShow: Boolean = false) {
        val wmStr = AppPrefs.getWatermark(this).trim()
        if (wmStr.isBlank() && !forceShow) {
            ivWatermarkOverlay.visibility = View.GONE
            return
        }
        if (wmStr.isBlank()) {
            Snackbar.make(ivPreview, "אין סימן מים מוגדר בהגדרות", Snackbar.LENGTH_SHORT).show()
            ivWatermarkOverlay.visibility = View.GONE
            return
        }

        val wmUri = runCatching { Uri.parse(wmStr) }.getOrNull()
        if (wmUri == null) {
            ivWatermarkOverlay.visibility = View.GONE
            return
        }
        val wmBmp = readBitmap(wmUri)
        if (wmBmp == null) {
            ivWatermarkOverlay.visibility = View.GONE
            return
        }
        ivWatermarkOverlay.setImageBitmap(wmBmp)
        ivWatermarkOverlay.visibility = View.VISIBLE

        ivWatermarkOverlay.post {
            val pad = 12
            ivWatermarkOverlay.x = (ivPreview.width - ivWatermarkOverlay.width - pad).toFloat().coerceAtLeast(0f)
            ivWatermarkOverlay.y = (ivPreview.height - ivWatermarkOverlay.height - pad).toFloat().coerceAtLeast(0f)
        }
    }

    private fun setupWatermarkOverlayDrag() {
        ivWatermarkOverlay.setOnTouchListener { v, ev ->
            if (ivWatermarkOverlay.visibility != View.VISIBLE) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wmDragging = true
                    wmDx = v.x - ev.rawX
                    wmDy = v.y - ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!wmDragging) return@setOnTouchListener false
                    v.x = ev.rawX + wmDx
                    v.y = ev.rawY + wmDy
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    wmDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun exportWatermarkPosNorm(): Pair<Float, Float> {
        if (ivWatermarkOverlay.visibility != View.VISIBLE) return Pair(-1f, -1f)
        val vw = ivPreview.width.coerceAtLeast(1).toFloat()
        val vh = ivPreview.height.coerceAtLeast(1).toFloat()
        val rx = (ivWatermarkOverlay.x / vw).coerceIn(0f, 1f)
        val ry = (ivWatermarkOverlay.y / vh).coerceIn(0f, 1f)
        return Pair(rx, ry)
    }

    private fun toggleBlurMode() {
        blurOverlay.isEnabled = true
        blurOverlay.allowRectangles = true
        blurOverlay.blurMode = !blurOverlay.blurMode
        Snackbar.make(
            ivPreview,
            if (blurOverlay.blurMode) "מצב טשטוש: גרור מלבנים על התמונה" else "מצב טשטוש: כבוי",
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun translateToHebrew() {
        val src = etCaption.text?.toString().orEmpty().trim()
        if (src.isBlank()) {
            Snackbar.make(ivPreview, "אין טקסט לתרגום", Snackbar.LENGTH_SHORT).show()
            return
        }
        val hasHeb = src.any { it in '\u0590'..'\u05FF' }
        if (hasHeb) {
            Snackbar.make(ivPreview, "הטקסט כבר בעברית", Snackbar.LENGTH_SHORT).show()
            return
        }

        Snackbar.make(ivPreview, "🔎 מזהה שפה...", Snackbar.LENGTH_SHORT).show()
        langId.identifyLanguage(src)
            .addOnSuccessListener { langCode ->
                val srcTag = if (langCode == "und") "en" else langCode
                val srcLang = TranslateLanguage.fromLanguageTag(srcTag) ?: TranslateLanguage.ENGLISH

                val opts = TranslatorOptions.Builder()
                    .setSourceLanguage(srcLang)
                    .setTargetLanguage(TranslateLanguage.HEBREW)
                    .build()

                translator?.close()
                translator = Translation.getClient(opts)
                val tr = translator!!

                val cond = DownloadConditions.Builder().build()
                Snackbar.make(ivPreview, "⬇️ מוריד מודל ומתרגם...", Snackbar.LENGTH_LONG).show()

                tr.downloadModelIfNeeded(cond)
                    .addOnSuccessListener {
                        tr.translate(src)
                            .addOnSuccessListener { out ->
                                etCaption.setText(out)
                                Snackbar.make(ivPreview, "✅ תורגם לעברית", Snackbar.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener { e ->
                                Snackbar.make(ivPreview, "❌ תרגום נכשל: ${e.message}", Snackbar.LENGTH_LONG).show()
                            }
                    }
                    .addOnFailureListener { e ->
                        Snackbar.make(ivPreview, "❌ הורדת מודל נכשלה: ${e.message}", Snackbar.LENGTH_LONG).show()
                    }
            }
            .addOnFailureListener { e ->
                Snackbar.make(ivPreview, "❌ זיהוי שפה נכשל: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
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
        val text = etCaption.text?.toString().orEmpty()
        val wm = AppPrefs.getWatermark(this).trim()

        val rects = blurOverlay.exportRectsNormalized()
        val rectsStr = rects.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }

        val (wmX, wmY) = exportWatermarkPosNorm()

        val data = Data.Builder()
            .putLong(SendWorker.KEY_SRC_CHAT_ID, srcChatId)
            .putLong(SendWorker.KEY_SRC_MESSAGE_ID, srcMsgId)
            .putString(SendWorker.KEY_TARGET_USERNAME, target)
            .putString(SendWorker.KEY_TEXT, text)
            .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, sendWithMedia)
            .putString(SendWorker.KEY_MEDIA_URI, mediaUri?.toString().orEmpty())
            .putString(SendWorker.KEY_MEDIA_MIME, mediaMime.orEmpty())
            .putString(SendWorker.KEY_WATERMARK_URI, wm)
            .putString(SendWorker.KEY_BLUR_RECTS, rectsStr)
            .putFloat(SendWorker.KEY_WM_X, wmX)
            .putFloat(SendWorker.KEY_WM_Y, wmY)
            .build()

        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(req)

        android.widget.Toast.makeText(this, "✅ נשלח לתור (חזור למסך הראשי)", android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun fetchSharpPreviewFromTelegram() {
        if (srcChatId == 0L || srcMsgId == 0L) return

        lifecycleScope.launch(Dispatchers.IO) {
            val msg = getMessageSync(srcChatId, srcMsgId) ?: return@launch

            val fileId: Int? = when (val c = msg.content) {
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

            if (fileId == null) return@launch

            val path = downloadFileToPathSync(fileId) ?: return@launch
            val bmp = BitmapFactory.decodeFile(path) ?: return@launch

            withContext(Dispatchers.Main) {
                ivPreview.setImageBitmap(bmp)
            }
        }
    }

    private fun getMessageSync(chatId: Long, msgId: Long): TdApi.Message? {
        val latch = CountDownLatch(1)
        var out: TdApi.Message? = null
        TdLibManager.send(TdApi.GetMessage(chatId, msgId)) { obj ->
            if (obj is TdApi.Message) out = obj
            latch.countDown()
        }
        latch.await(12, TimeUnit.SECONDS)
        return out
    }

    private fun downloadFileToPathSync(fileId: Int): String? {
        TdLibManager.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { }

        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            var f: TdApi.File? = null
            TdLibManager.send(TdApi.GetFile(fileId)) { obj ->
                if (obj is TdApi.File) f = obj
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)

            val done = f?.local?.isDownloadingCompleted ?: false
            val p = f?.local?.path
            if (done && !p.isNullOrBlank()) return p
            Thread.sleep(250)
        }
        return null
    }
}
