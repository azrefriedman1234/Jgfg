package com.pasiflonet.mobile.ui.editor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayEditorView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    enum class Mode { NONE, WATERMARK, BLUR }

    private var baseBitmap: Bitmap? = null
    private var watermarkBitmap: Bitmap? = null

    // watermark anchor in normalized [0..1] coordinates
    private var wmX = 0.75f
    private var wmY = 0.85f
    private var wmEnabled = false

    // blur rectangles in normalized coords
    private val blurRects = mutableListOf<RectF>()
    private var curRect: RectF? = null
    private var mode: Mode = Mode.NONE

    // drawing helpers
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setImageBitmap(bm: Bitmap?) {
        baseBitmap = bm
        invalidate()
    }

    fun setWatermarkBitmap(bm: Bitmap?) {
        watermarkBitmap = bm
        invalidate()
    }

    fun setMode(m: Mode) {
        mode = m
        // אם בוחרים WATERMARK – נוודא שהוא מופעל
        if (m == Mode.WATERMARK) wmEnabled = true
        invalidate()
    }

    fun clearEdits() {
        blurRects.clear()
        curRect = null
        wmEnabled = false
        invalidate()
    }

    fun exportEdits(): Edits {
        return Edits(
            watermarkEnabled = wmEnabled && watermarkBitmap != null,
            wmX = wmX, wmY = wmY,
            blurRects = blurRects.toList()
        )
    }

    data class Edits(
        val watermarkEnabled: Boolean,
        val wmX: Float,
        val wmY: Float,
        val blurRects: List<RectF>
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bm = baseBitmap ?: run {
            // placeholder
            paint.color = Color.argb(60, 255, 255, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            return
        }

        // Fit-center
        val dst = fitCenterDst(bm.width.toFloat(), bm.height.toFloat())
        canvas.drawBitmap(bm, null, dst, null)

        // Draw blur overlays (preview)
        for (rN in blurRects) {
            val r = denormRect(rN, dst)
            // preview effect: semi-transparent overlay + border
            paint.color = Color.argb(120, 0, 0, 0)
            canvas.drawRect(r, paint)
            canvas.drawRect(r, stroke)
        }
        curRect?.let {
            val r = denormRect(it, dst)
            paint.color = Color.argb(80, 255, 255, 255)
            canvas.drawRect(r, paint)
            canvas.drawRect(r, stroke)
        }

        // Watermark preview
        if (wmEnabled && watermarkBitmap != null) {
            val wm = watermarkBitmap!!
            val wmW = dst.width() * 0.22f
            val scale = wmW / wm.width.toFloat()
            val wmH = wm.height.toFloat() * scale

            val x = dst.left + wmX * dst.width()
            val y = dst.top + wmY * dst.height()

            val left = x - wmW / 2f
            val top = y - wmH / 2f
            val rect = RectF(left, top, left + wmW, top + wmH)

            paint.alpha = 220
            canvas.drawBitmap(wm, null, rect, paint)

            // anchor dot
            paint.alpha = 255
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 8f, paint)
        }
    
        // === BLUR_PREVIEW_DRAW ===
        runCatching {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = 0x55FFFFFF.toInt()
            }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = 0xFFFFFFFF.toInt()
            }

            // Support RectF lists directly
            for (r in blurRects) {
                if (r is android.graphics.RectF) {
                    canvas.drawRect(r, fill)
                    canvas.drawRect(r, stroke)
                }
            }
        }
        // === END BLUR_PREVIEW_DRAW ===
            

        // DRAW_BLUR_PREVIEW_RECT_SAFE
        runCatching {
            val stroke = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f
                color = android.graphics.Color.argb(220, 255, 0, 0)
            }
            val fill = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.FILL
                color = android.graphics.Color.argb(50, 255, 0, 0)
            }

            // try to read blur rects from common fields (no compile-time types)
            val listAny: List<Any?> = runCatching {
                val f = this@OverlayEditorView.javaClass.declaredFields.firstOrNull { it.name in setOf("blurRects","mBlurRects","rects","blurRectList") }
                f?.isAccessible = True
                (f?.get(this@OverlayEditorView) as? List<*>)?.toList() ?: emptyList()
            }.getOrElse { emptyList() }

            fun getFloat(obj: Any, name: String): Float? = runCatching {
                val fld = obj.javaClass.getDeclaredField(name)
                fld.isAccessible = true
                val v = fld.get(obj)
                when (v) {
                    is Float -> v
                    is Double -> v.toFloat()
                    is Int -> v.toFloat()
                    else -> null
                }
            }.getOrNull()

            for (rr in listAny) {
                if (rr == null) continue

                // Case 1: rr is RectF / Rect
                if (rr is android.graphics.RectF) {
                    canvas.drawRect(rr, fill)
                    canvas.drawRect(rr, stroke)
                    continue
                }
                if (rr is android.graphics.Rect) {
                    val rf = android.graphics.RectF(rr)
                    canvas.drawRect(rf, fill)
                    canvas.drawRect(rf, stroke)
                    continue
                }

                // Case 2: normalized fields l,t,r,b
                val lN = getFloat(rr, "l")
                val tN = getFloat(rr, "t")
                val rN = getFloat(rr, "r")
                val bN = getFloat(rr, "b")
                if (lN != null && tN != null && rN != null && bN != null) {
                    // map by dst if exists; else full view
                    val dstRectF = runCatching {
                        val df = this@OverlayEditorView.javaClass.getDeclaredField("dst")
                        df.isAccessible = true
                        df.get(this@OverlayEditorView) as? android.graphics.RectF
                    }.getOrNull()

                    val dst = dstRectF ?: android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
                    val l = dst.left + lN * dst.width()
                    val t = dst.top + tN * dst.height()
                    val r = dst.left + rN * dst.width()
                    val b = dst.top + bN * dst.height()
                    val rc = android.graphics.RectF(l, t, r, b)
                    canvas.drawRect(rc, fill)
                    canvas.drawRect(rc, stroke)
                }
            }
        }

}
            val fill = android.graphics.Paint().apply {
                style = android.graphics.Paint.Style.FILL
                color = android.graphics.Color.argb(50, 255, 0, 0)
            }
            val list = try { blurRects } catch (_: Throwable) { emptyList<com.pasiflonet.mobile.worker.SendWorker.NRect>() }
            for (r in list) {
                val l = dst.left + r.l * dst.width()
                val t = dst.top + r.t * dst.height()
                val rr = dst.left + r.r * dst.width()
                val bb = dst.top + r.b * dst.height()
                val rc = android.graphics.RectF(l, t, rr, bb)
                canvas.drawRect(rc, fill)
                canvas.drawRect(rc, stroke)
            }
        }

}

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bm = baseBitmap ?: return false
        val dst = fitCenterDst(bm.width.toFloat(), bm.height.toFloat())

        fun inDst(x: Float, y: Float) = x >= dst.left && x <= dst.right && y >= dst.top && y <= dst.bottom

        val x = event.x
        val y = event.y

        if (!inDst(x, y)) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                when (mode) {
                    Mode.WATERMARK -> {
                        // set anchor
                        wmX = ((x - dst.left) / dst.width()).coerceIn(0f, 1f)
                        wmY = ((y - dst.top) / dst.height()).coerceIn(0f, 1f)
                        wmEnabled = true
                        invalidate()
                    }
                    Mode.BLUR -> {
                        val nx = ((x - dst.left) / dst.width()).coerceIn(0f, 1f)
                        val ny = ((y - dst.top) / dst.height()).coerceIn(0f, 1f)
                        curRect = RectF(nx, ny, nx, ny)
                        invalidate()
                    }
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.BLUR && curRect != null) {
                    val nx = ((x - dst.left) / dst.width()).coerceIn(0f, 1f)
                    val ny = ((y - dst.top) / dst.height()).coerceIn(0f, 1f)
                    curRect!!.right = nx
                    curRect!!.bottom = ny
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.BLUR && curRect != null) {
                    val r = normalizeRect(curRect!!)
                    val minSize = 0.03f
                    if (abs(r.width()) >= minSize && abs(r.height()) >= minSize) {
                        blurRects.add(r)
                    }
                    curRect = null
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun fitCenterDst(bw: Float, bh: Float): RectF {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val scale = min(vw / bw, vh / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }

    private fun normalizeRect(r: RectF): RectF {
        val l = min(r.left, r.right)
        val t = min(r.top, r.bottom)
        val rr = max(r.left, r.right)
        val bb = max(r.top, r.bottom)
        return RectF(l, t, rr, bb)
    }

    private fun denormRect(rN: RectF, dst: RectF): RectF {
        return RectF(
            dst.left + rN.left * dst.width(),
            dst.top + rN.top * dst.height(),
            dst.left + rN.right * dst.width(),
            dst.top + rN.bottom * dst.height()
        )
    }
}
