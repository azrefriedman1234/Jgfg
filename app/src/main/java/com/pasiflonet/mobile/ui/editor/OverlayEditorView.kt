package com.pasiflonet.mobile.ui.editor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Clean, compiling overlay editor:
 * - Base bitmap preview (fit center)
 * - Watermark bitmap (scaled to ~18% of base width) draggable
 * - Blur rectangles preview: drag to create rectangles
 * - Rects are stored normalized 0..1 relative to the displayed image
 */
class OverlayEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val MODE_WATERMARK = 0
        const val MODE_BLUR = 1
    }

    // --- Public state ---
    var interactionMode: Int = MODE_WATERMARK
        set(value) {
            field = value
            invalidate()
        }

    var baseBitmap: Bitmap? = null
        set(value) {
            field = value
            recomputeContentRect()
            invalidate()
        }

    var watermarkBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** normalized 0..1 inside image rect */
    var watermarkNx: Float = 0.80f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    /** normalized 0..1 inside image rect */
    var watermarkNy: Float = 0.80f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    /** normalized blur rects in image space 0..1 */
    private val blurRectsN: MutableList<RectF> = mutableListOf()

    fun setBlurRectsNormalized(rects: List<RectF>) {
        blurRectsN.clear()
        rects.forEach { r ->
            blurRectsN.add(normRect(r))
        }
        invalidate()
    }

    fun getBlurRectsNormalized(): List<RectF> = blurRectsN.toList()

    /**
     * Serialize rects as "l,t,r,b;l,t,r,b" (normalized 0..1)
     */
    fun getBlurRectsString(): String {
        return blurRectsN.joinToString(";") { r ->
            "${r.left},${r.top},${r.right},${r.bottom}"
        }
    }

    fun setBlurRectsString(s: String) {
        blurRectsN.clear()
        val t = s.trim()
        if (t.isEmpty()) {
            invalidate()
            return
        }
        t.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { part ->
                val nums = part.split(",").map { it.trim() }
                if (nums.size == 4) {
                    val l = nums[0].toFloatOrNull()
                    val top = nums[1].toFloatOrNull()
                    val r = nums[2].toFloatOrNull()
                    val b = nums[3].toFloatOrNull()
                    if (l != null && top != null && r != null && b != null) {
                        blurRectsN.add(normRect(RectF(l, top, r, b)))
                    }
                }
            }
        invalidate()
    }

    fun clearBlurRects() {
        blurRectsN.clear()
        invalidate()
    }

    // --- Drawing helpers ---
    private val paintBitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paintWm = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val blurStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFFFFC107.toInt() // amber stroke
    }
    private val blurFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x55FFC107 // translucent fill
    }

    private val wmStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0xFF00E5FF.toInt()
    }

    // Rect of the fitted base image inside the view
    private val contentRect = RectF()
    private val tmpRectF = RectF()

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun recomputeContentRect() {
        val b = baseBitmap ?: run {
            contentRect.set(0f, 0f, 0f, 0f)
            return
        }
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val bw = b.width.toFloat()
        val bh = b.height.toFloat()
        if (bw <= 0f || bh <= 0f) return

        val scale = min(vw / bw, vh / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        contentRect.set(left, top, left + dw, top + dh)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeContentRect()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val base = baseBitmap
        if (base != null && contentRect.width() > 0f && contentRect.height() > 0f) {
            // Draw base bitmap fit-center
            val src = Rect(0, 0, base.width, base.height)
            canvas.drawBitmap(base, src, contentRect, paintBitmap)

            // Draw blur preview rectangles
            blurRectsN.forEach { nr ->
                val vr = normToViewRect(nr)
                canvas.drawRect(vr, blurFill)
                canvas.drawRect(vr, blurStroke)
            }

            // Draw watermark if exists
            val wm = watermarkBitmap
            if (wm != null) {
                val wmRect = computeWatermarkRect(wm)
                // draw watermark bitmap
                val wmSrc = Rect(0, 0, wm.width, wm.height)
                canvas.drawBitmap(wm, wmSrc, wmRect, paintWm)
                // outline
                canvas.drawRect(wmRect, wmStroke)
            }
        }
    }

    private fun computeWatermarkRect(wm: Bitmap): RectF {
        // watermark width approx 18% of displayed base width
        val targetW = max(24f, contentRect.width() * 0.18f)
        val aspect = wm.height.toFloat() / max(1f, wm.width.toFloat())
        val targetH = targetW * aspect

        val maxX = contentRect.width() - targetW
        val maxY = contentRect.height() - targetH

        val x = contentRect.left + (watermarkNx.coerceIn(0f, 1f) * max(0f, maxX))
        val y = contentRect.top + (watermarkNy.coerceIn(0f, 1f) * max(0f, maxY))

        tmpRectF.set(x, y, x + targetW, y + targetH)
        return tmpRectF
    }

    // --- Touch interaction ---
    private var draggingWm = false
    private var dragDx = 0f
    private var dragDy = 0f

    private var blurDragging = false
    private val blurStart = PointF()
    private val blurCurrent = PointF()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val base = baseBitmap ?: return false
        if (contentRect.width() <= 0f || contentRect.height() <= 0f) return false

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)

                if (interactionMode == MODE_WATERMARK && watermarkBitmap != null) {
                    val wmRect = computeWatermarkRect(watermarkBitmap!!)
                    if (wmRect.contains(x, y)) {
                        draggingWm = true
                        dragDx = x - wmRect.left
                        dragDy = y - wmRect.top
                        return true
                    }
                }

                if (interactionMode == MODE_BLUR) {
                    blurDragging = true
                    blurStart.set(x, y)
                    blurCurrent.set(x, y)
                    invalidate()
                    return true
                }

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (draggingWm && watermarkBitmap != null) {
                    val wm = watermarkBitmap!!
                    val targetW = max(24f, contentRect.width() * 0.18f)
                    val aspect = wm.height.toFloat() / max(1f, wm.width.toFloat())
                    val targetH = targetW * aspect

                    val maxX = contentRect.width() - targetW
                    val maxY = contentRect.height() - targetH

                    val left = (x - dragDx).coerceIn(contentRect.left, contentRect.left + max(0f, maxX))
                    val top = (y - dragDy).coerceIn(contentRect.top, contentRect.top + max(0f, maxY))

                    // update normalized
                    watermarkNx = if (maxX <= 1f) 0f else (left - contentRect.left) / maxX
                    watermarkNy = if (maxY <= 1f) 0f else (top - contentRect.top) / maxY

                    invalidate()
                    return true
                }

                if (blurDragging) {
                    blurCurrent.set(x, y)
                    invalidate()
                    return true
                }

                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)

                if (draggingWm) {
                    draggingWm = false
                    invalidate()
                    return true
                }

                if (blurDragging) {
                    blurDragging = false
                    blurCurrent.set(x, y)

                    // Create rect in view coords then convert to normalized
                    val vr = RectF(
                        min(blurStart.x, blurCurrent.x),
                        min(blurStart.y, blurCurrent.y),
                        max(blurStart.x, blurCurrent.x),
                        max(blurStart.y, blurCurrent.y),
                    )

                    // ignore tiny rects
                    if (vr.width() > dp(8f) && vr.height() > dp(8f)) {
                        val nr = viewToNormRect(vr)
                        blurRectsN.add(normRect(nr))
                    }

                    invalidate()
                    return true
                }

                return true
            }
        }

        return false
    }

    // --- Norm conversions (0..1 relative to contentRect) ---
    private fun normRect(r: RectF): RectF {
        val l = r.left.coerceIn(0f, 1f)
        val t = r.top.coerceIn(0f, 1f)
        val rr = r.right.coerceIn(0f, 1f)
        val b = r.bottom.coerceIn(0f, 1f)
        val left = min(l, rr)
        val right = max(l, rr)
        val top = min(t, b)
        val bottom = max(t, b)
        return RectF(left, top, right, bottom)
    }

    private fun viewToNormRect(viewRect: RectF): RectF {
        val w = contentRect.width()
        val h = contentRect.height()
        if (w <= 1f || h <= 1f) return RectF(0f, 0f, 0f, 0f)

        val cl = ((viewRect.left - contentRect.left) / w).coerceIn(0f, 1f)
        val ct = ((viewRect.top - contentRect.top) / h).coerceIn(0f, 1f)
        val cr = ((viewRect.right - contentRect.left) / w).coerceIn(0f, 1f)
        val cb = ((viewRect.bottom - contentRect.top) / h).coerceIn(0f, 1f)
        return RectF(cl, ct, cr, cb)
    }

    private fun normToViewRect(nr: RectF): RectF {
        val w = contentRect.width()
        val h = contentRect.height()
        val l = contentRect.left + (nr.left * w)
        val t = contentRect.top + (nr.top * h)
        val r = contentRect.left + (nr.right * w)
        val b = contentRect.top + (nr.bottom * h)
        return RectF(l, t, r, b)
    }
}
