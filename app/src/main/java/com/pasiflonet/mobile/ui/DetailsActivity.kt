package com.pasiflonet.mobile.ui

import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.pasiflonet.mobile.R
import com.pasiflonet.mobile.data.AppPrefs
import com.pasiflonet.mobile.worker.SendWorker
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SRC_CHAT_ID = "src_chat_id"
        const val EXTRA_SRC_MESSAGE_ID = "src_message_id"
        const val EXTRA_TEXT = "text"
        const val EXTRA_MEDIA_URI = "media_uri"     // optional content://
        const val EXTRA_MEDIA_MIME = "media_mime"   // optional
    }

    private lateinit var ivPreview: ImageView
    private lateinit var blurOverlay: BlurOverlayView
    private lateinit var ivWatermarkOverlay: ImageView
    private lateinit var tvMeta: TextView
    private lateinit var etCaption: TextInputEditText

    private var srcChatId: Long = 0L
    private var srcMsgId: Long = 0L

    private var mediaUri: Uri? = null
    private var mediaMime: String? = null

    private var originalBitmap: Bitmap? = null
    private var workingBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        ivPreview = findViewById(R.id.ivPreview)
        blurOverlay = findViewById(R.id.blurOverlay)
        ivWatermarkOverlay = findViewById(R.id.ivWatermarkOverlay)
        tvMeta = findViewById(R.id.tvMeta)
        etCaption = findViewById(R.id.etCaption)

        srcChatId = intent.getLongExtra(EXTRA_SRC_CHAT_ID, 0L)
        srcMsgId = intent.getLongExtra(EXTRA_SRC_MESSAGE_ID, 0L)
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        mediaMime = intent.getStringExtra(EXTRA_MEDIA_MIME)
        mediaUri = intent.getStringExtra(EXTRA_MEDIA_URI)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

        etCaption.setText(text)

        tvMeta.text = buildMetaString()

        loadPreview()

        // Blur overlay callback: apply pixelate/blur on bitmap area after a rectangle is finished
        blurOverlay.onRectFinished = { rectOnView ->
            applyBlurRect(rectOnView)
        }

        // Watermark overlay is draggable (when visible)
        setupDraggableOverlay(ivWatermarkOverlay)

        findViewById<Button>(R.id.btnWatermark).setOnClickListener { toggleOrLoadWatermark() }
        findViewById<Button>(R.id.btnBlur).setOnClickListener { toggleBlurMode() }
        findViewById<Button>(R.id.btnTranslate).setOnClickListener { translateStub() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { enqueueSend() }
    }

    private fun buildMetaString(): String {
        val sb = StringBuilder()
        sb.append("chatId=").append(srcChatId).append(" | msgId=").append(srcMsgId)
        sb.append("\nmedia=").append(mediaUri?.toString() ?: "none")
        if (!mediaMime.isNullOrBlank()) sb.append(" (").append(mediaMime).append(")")
        sb.append("\nיעד: ").append(AppPrefs.getTargetUsername(this).ifBlank { "(לא הוגדר)" })
        return sb.toString()
    }

    private fun loadPreview() {
        val uri = mediaUri
        if (uri == null) {
            ivPreview.setImageResource(android.R.drawable.ic_menu_report_image)
            blurOverlay.enabledForImage = false
            return
        }

        val bmp = readBitmap(uri)
        if (bmp != null) {
            originalBitmap = bmp
            workingBitmap = bmp.copy(Bitmap.Config.ARGB_8888, true)
            ivPreview.setImageBitmap(workingBitmap)
            blurOverlay.enabledForImage = true
        } else {
            ivPreview.setImageResource(android.R.drawable.ic_media_play)
            blurOverlay.enabledForImage = false
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

    private fun toggleOrLoadWatermark() {
        val bmp = workingBitmap
        if (bmp == null) {
            Snackbar.make(ivPreview, "סימן מים עובד כרגע לתמונות (בווידאו נטפל אחר כך).", Snackbar.LENGTH_SHORT).show()
            return
        }

        if (ivWatermarkOverlay.visibility == View.VISIBLE) {
            ivWatermarkOverlay.visibility = View.GONE
            Snackbar.make(ivPreview, "סימן מים: הוסתר", Snackbar.LENGTH_SHORT).show()
            return
        }

        val wmStr = AppPrefs.getWatermark(this).trim()
        if (wmStr.isBlank()) {
            Snackbar.make(ivPreview, "לא הוגדר סימן מים בהגדרות (צריך URI מהגלריה).", Snackbar.LENGTH_LONG).show()
            return
        }

        val wmUri = runCatching { Uri.parse(wmStr) }.getOrNull()
        if (wmUri == null) {
            Snackbar.make(ivPreview, "URI של סימן מים לא תקין", Snackbar.LENGTH_SHORT).show()
            return
        }

        val wmBmp = readBitmap(wmUri)
        if (wmBmp == null) {
            Snackbar.make(ivPreview, "לא הצלחתי לקרוא את תמונת הסימן מים", Snackbar.LENGTH_SHORT).show()
            return
        }

        ivWatermarkOverlay.setImageBitmap(wmBmp)
        ivWatermarkOverlay.visibility = View.VISIBLE

        // default position bottom-right (inside preview area)
        ivWatermarkOverlay.post {
            val pad = 16f
            ivWatermarkOverlay.x = max(ivPreview.x + pad, ivPreview.x + ivPreview.width - ivWatermarkOverlay.width - pad)
            ivWatermarkOverlay.y = max(ivPreview.y + pad, ivPreview.y + ivPreview.height - ivWatermarkOverlay.height - pad)
        }

        Snackbar.make(ivPreview, "✅ גרור את הסימן מים למיקום הרצוי", Snackbar.LENGTH_LONG).show()
    }

    private fun toggleBlurMode() {
        if (!blurOverlay.enabledForImage) {
            Snackbar.make(ivPreview, "טשטוש עובד כרגע לתמונות. בווידאו נטפל אחר כך.", Snackbar.LENGTH_SHORT).show()
            return
        }
        blurOverlay.blurMode = !blurOverlay.blurMode
        Snackbar.make(
            ivPreview,
            if (blurOverlay.blurMode) "מצב טשטוש: גרור מלבן על התצוגה" else "מצב טשטוש: כבוי",
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun applyBlurRect(rectOnView: RectF) {
        val base = workingBitmap ?: return
        // Map rect from view coords to bitmap coords (approx using fitCenter scale)
        val mapped = mapViewRectToBitmapRect(rectOnView, ivPreview, base) ?: return

        val out = base.copy(Bitmap.Config.ARGB_8888, true)

        // Pixelate: crop -> downscale -> upscale back
        val x0 = mapped.left.coerceIn(0, out.width - 1)
        val y0 = mapped.top.coerceIn(0, out.height - 1)
        val x1 = mapped.right.coerceIn(x0 + 1, out.width)
        val y1 = mapped.bottom.coerceIn(y0 + 1, out.height)

        val w = (x1 - x0).coerceAtLeast(2)
        val h = (y1 - y0).coerceAtLeast(2)

        val region = Bitmap.createBitmap(out, x0, y0, w, h)
        val downW = max(8, w / 18)
        val downH = max(8, h / 18)
        val small = Bitmap.createScaledBitmap(region, downW, downH, true)
        val pix = Bitmap.createScaledBitmap(small, w, h, false)

        val c = Canvas(out)
        c.drawBitmap(pix, x0.toFloat(), y0.toFloat(), null)

        workingBitmap = out
        ivPreview.setImageBitmap(out)
        Snackbar.make(ivPreview, "✅ טשטוש הוחל", Snackbar.LENGTH_SHORT).show()
    }

    private fun mapViewRectToBitmapRect(r: RectF, iv: ImageView, bmp: Bitmap): Rect? {
        val d = iv.drawable ?: return null

        // ImageView image matrix mapping
        val m = Matrix()
        iv.imageMatrix.invert(m)

        val pts = floatArrayOf(r.left, r.top, r.right, r.bottom)
        m.mapPoints(pts)

        val left = min(pts[0], pts[2]).toInt()
        val top = min(pts[1], pts[3]).toInt()
        val right = max(pts[0], pts[2]).toInt()
        val bottom = max(pts[1], pts[3]).toInt()

        // Clamp to drawable size then to bitmap size (usually same if bitmap drawable)
        val dw = d.intrinsicWidth.takeIf { it > 0 } ?: bmp.width
        val dh = d.intrinsicHeight.takeIf { it > 0 } ?: bmp.height

        val cl = left.coerceIn(0, dw - 1)
        val ct = top.coerceIn(0, dh - 1)
        val cr = right.coerceIn(cl + 1, dw)
        val cb = bottom.coerceIn(ct + 1, dh)

        // If drawable != bitmap size, scale to bitmap
        val sx = bmp.width.toFloat() / dw.toFloat()
        val sy = bmp.height.toFloat() / dh.toFloat()

        return Rect(
            (cl * sx).toInt().coerceIn(0, bmp.width - 1),
            (ct * sy).toInt().coerceIn(0, bmp.height - 1),
            (cr * sx).toInt().coerceIn(1, bmp.width),
            (cb * sy).toInt().coerceIn(1, bmp.height)
        )
    }

    private fun translateStub() {
        Snackbar.make(ivPreview, "תרגום חינם on-device: נוסיף אחרי שמסיימים יציבות שליחה/עריכה.", Snackbar.LENGTH_SHORT).show()
    }

    private fun enqueueSend() {
        val target = AppPrefs.getTargetUsername(this).trim()
        if (target.isBlank()) {
            Snackbar.make(ivPreview, "❌ לא הוגדר @username יעד", Snackbar.LENGTH_SHORT).show()
            return
        }
        if (srcChatId == 0L || srcMsgId == 0L) {
            Snackbar.make(ivPreview, "❌ חסרים מזהים של הודעה מקורית", Snackbar.LENGTH_SHORT).show()
            return
        }

        val text = etCaption.text?.toString().orEmpty()

        // If we have an edited image -> save to cache and send as media
        var mediaPath: String? = null
        if (workingBitmap != null && mediaUri != null) {
            mediaPath = saveWorkingBitmapToCache()
        }

        val data = Data.Builder()
            .putLong(SendWorker.KEY_SRC_CHAT_ID, srcChatId)
            .putLong(SendWorker.KEY_SRC_MESSAGE_ID, srcMsgId)
            .putString(SendWorker.KEY_TARGET_USERNAME, target)
            .putString(SendWorker.KEY_TEXT, text)
            .putBoolean(SendWorker.KEY_SEND_WITH_MEDIA, !mediaPath.isNullOrBlank())
            .putString(SendWorker.KEY_MEDIA_PATH, mediaPath ?: "")
            .build()

        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(data)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(req)
        Snackbar.make(ivPreview, "✅ נשלח לתור שליחה. בדוק בערוץ יעד.", Snackbar.LENGTH_LONG).show()
    }

    private fun saveWorkingBitmapToCache(): String? {
        return try {
            val bmp = workingBitmap ?: return null
            val f = File(cacheDir, "pasiflonet_send_${System.currentTimeMillis()}.jpg")
            FileOutputStream(f).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            f.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    private fun setupDraggableOverlay(v: View) {
        v.setOnTouchListener(object : View.OnTouchListener {
            var dX = 0f
            var dY = 0f
            override fun onTouch(view: View, ev: MotionEvent): Boolean {
                if (view.visibility != View.VISIBLE) return false
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = view.x - ev.rawX
                        dY = view.y - ev.rawY
                        view.parent.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        view.x = ev.rawX + dX
                        view.y = ev.rawY + dY
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.parent.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
                return false
            }
        })
    }
}
