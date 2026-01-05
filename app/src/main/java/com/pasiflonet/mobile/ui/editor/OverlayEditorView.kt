package com.pasiflonet.mobile.ui.editor

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var baseBitmap: Bitmap? = null
    private var watermarkBitmap: Bitmap? = null
    private var watermarkUri: Uri? = null

    // base image drawn rect inside view
    private val contentRect = RectF()

    // watermark normalized top-left (0..1) relative to contentRect
    private var wmNx = 0.70f
    private var wmNy = 0.70f

    // blur rects normalized (0..1) relative to contentRect
    private val blurRectsN = mutableListOf<RectF>()
    private var drawingBlur: RectF? = null

    private val paintBitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val blurFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33FFFFFF
    }
    private val blurStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFFFFFFFF.toInt()
    }
    private val wmStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = 0xFFFFFFFF.toInt()
    }

    private enum class Mode { NONE, DRAG_WM, DRAW_BLUR }
    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var wmDownNx = 0f
    private var wmDownNy = 0f

    fun setBaseBitmap(bm: Bitmap?) { baseBitmap = bm; invalidate() }
    fun setWatermarkBitmap(bm: Bitmap?) { watermarkBitmap = bm; invalidate() }
    fun setWatermarkUri(uri: Uri?) { watermarkUri = uri }
    fun getWatermarkUriString(): String = watermarkUri?.toString().orEmpty()
    fun getWatermarkNormX(): Float = wmNx
    fun getWatermarkNormY(): Float = wmNy

    fun clearBlurRects() { blurRectsN.clear(); drawingBlur = null; invalidate() }

    fun exportBlurRectsString(): String =
        blurRectsN.joinToString(";") { r ->
            "${r.left.coerceIn(0f,1f)},${r.top.coerceIn(0f,1f)},${r.right.coerceIn(0f,1f)},${r.bottom.coerceIn(0f,1f)}"
        }

    fun importBlurRectsString(s: String) {
        blurRectsN.clear()
        val parts = s.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        for (p in parts) {
            val nums = p.split(",").mapNotNull { it.trim().toFloatOrNull() }
            if (nums.size == 4) {
                val l = min(nums[0], nums[2]).coerceIn(0f, 1f)
                val t = min(nums[1], nums[3]).coerceIn(0f, 1f)
                val r = max(nums[0], nums[2]).coerceIn(0f, 1f)
                val b = max(nums[1], nums[3]).coerceIn(0f, 1f)
                if (r - l > 0.005f && b - t > 0.005f) blurRectsN.add(RectF(l,t,r,b))
            }
        }
        invalidate()
    }

    private fun computeContentRect() {
        val bm = baseBitmap
        if (bm == null || width <= 0 || height <= 0) {
            contentRect.set(0f, 0f, width.toFloat(), height.toFloat())
            return
        }
        val vw = width.toFloat()
        val vh = height.toFloat()
        val bw = bm.width.toFloat().coerceAtLeast(1f)
        val bh = bm.height.toFloat().coerceAtLeast(1f)
        val scale = min(vw / bw, vh / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        contentRect.set(left, top, left + dw, top + dh)
    }

    private fun normToViewRect(n: RectF): RectF {
        computeContentRect()
        val l = contentRect.left + n.left * contentRect.width()
        val t = contentRect.top + n.top * contentRect.height()
        val r = contentRect.left + n.right * contentRect.width()
        val b = contentRect.top + n.bottom * contentRect.height()
        return RectF(l, t, r, b)
    }

    private fun watermarkViewRect(): RectF? {
        val wm = watermarkBitmap ?: return null
        computeContentRect()
        val targetW = contentRect.width() * 0.22f
        val scale = (targetW / wm.width.toFloat()).coerceAtMost(6f)
        val w = wm.width * scale
        val h = wm.height * scale

        val x = contentRect.left + wmNx * contentRect.width()
        val y = contentRect.top + wmNy * contentRect.height()

        val left = x.coerceIn(contentRect.left, contentRect.right - w)
        val top = y.coerceIn(contentRect.top, contentRect.bottom - h)
        return RectF(left, top, left + w, top + h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        computeContentRect()

        val bm = baseBitmap
        if (bm != null) {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bm, null, contentRect, paintBitmap)
        } else {
            canvas.drawColor(Color.DKGRAY)
        }

        for (n in blurRectsN) {
            val r = normToViewRect(n)
            canvas.drawRect(r, blurFill)
            canvas.drawRect(r, blurStroke)
        }
        drawingBlur?.let { r ->
            canvas.drawRect(r, blurFill)
            canvas.drawRect(r, blurStroke)
        }

        val wm = watermarkBitmap
        if (wm != null) {
            val dst = watermarkViewRect()
            if (dst != null) {
                canvas.drawBitmap(wm, null, dst, paintBitmap)
                canvas.drawRect(dst, wmStroke)
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y
                val wmRect = watermarkViewRect()
                mode = if (wmRect != null && wmRect.contains(x, y)) {
                    wmDownNx = wmNx; wmDownNy = wmNy
                    Mode.DRAG_WM
                } else {
                    computeContentRect()
                    if (contentRect.contains(x, y)) {
                        drawingBlur = RectF(x, y, x, y)
                        Mode.DRAW_BLUR
                    } else Mode.NONE
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.DRAG_WM -> {
                        val dx = x - downX
                        val dy = y - downY
                        computeContentRect()
                        if (contentRect.width() > 0f && contentRect.height() > 0f) {
                            wmNx = (wmDownNx + dx / contentRect.width()).coerceIn(0f, 1f)
                            wmNy = (wmDownNy + dy / contentRect.height()).coerceIn(0f, 1f)
                        }
                        invalidate()
                        return true
                    }
                    Mode.DRAW_BLUR -> {
                        drawingBlur?.let { r ->
                            r.right = x
                            r.bottom = y
                            computeContentRect()
                            r.left = r.left.coerceIn(contentRect.left, contentRect.right)
                            r.top = r.top.coerceIn(contentRect.top, contentRect.bottom)
                            r.right = r.right.coerceIn(contentRect.left, contentRect.right)
                            r.bottom = r.bottom.coerceIn(contentRect.top, contentRect.bottom)
                        }
                        invalidate()
                        return true
                    }
                    else -> {}
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.DRAW_BLUR) {
                    val r = drawingBlur
                    drawingBlur = null
                    if (r != null) {
                        val left = min(r.left, r.right)
                        val top = min(r.top, r.bottom)
                        val right = max(r.left, r.right)
                        val bottom = max(r.top, r.bottom)
                        val w = abs(right - left)
                        val h = abs(bottom - top)
                        if (w > 10f && h > 10f) {
                            computeContentRect()
                            val nl = ((left - contentRect.left) / contentRect.width()).coerceIn(0f, 1f)
                            val nt = ((top - contentRect.top) / contentRect.height()).coerceIn(0f, 1f)
                            val nr = ((right - contentRect.left) / contentRect.width()).coerceIn(0f, 1f)
                            val nb = ((bottom - contentRect.top) / contentRect.height()).coerceIn(0f, 1f)
                            blurRectsN.add(RectF(min(nl,nr), min(nt,nb), max(nl,nr), max(nt,nb)))
                        }
                    }
                }
                mode = Mode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
