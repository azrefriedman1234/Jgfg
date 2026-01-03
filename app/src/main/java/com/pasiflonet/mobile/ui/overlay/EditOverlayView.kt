package com.pasiflonet.mobile.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Overlay for touch editing:
 * - Watermark mode: tap to place a dot.
 * - Blur mode: drag to create rectangles (strong blur regions).
 *
 * Coordinates are normalized (0..1) by view size.
 */
class EditOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { NONE, WATERMARK, BLUR }

    private var mode: Mode = Mode.NONE

    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00A3A3.toInt()
        style = Paint.Style.FILL
    }

    private val paintRect = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FF5A5F.toInt()
        style = Paint.Style.FILL
    }

    private val paintRectStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF5A5F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private var watermarkX: Float? = null
    private var watermarkY: Float? = null

    private val blurRects = mutableListOf<BlurRectNorm>()
    private var activeRect: RectF? = null
    private var startX = 0f
    private var startY = 0f

    fun setMode(m: Mode) {
        mode = m
        activeRect = null
        invalidate()
    }

    fun getPlan(): EditPlan = EditPlan(
        watermarkX = watermarkX,
        watermarkY = watermarkY,
        blurRects = blurRects.toList()
    )

    fun setPlan(plan: EditPlan) {
        watermarkX = plan.watermarkX
        watermarkY = plan.watermarkY
        blurRects.clear()
        blurRects.addAll(plan.blurRects)
        invalidate()
    }

    fun clearAll() {
        watermarkX = null
        watermarkY = null
        blurRects.clear()
        activeRect = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // draw blur rects
        for (r in blurRects) {
            val rect = RectF(r.left * width, r.top * height, r.right * width, r.bottom * height)
            canvas.drawRoundRect(rect, 14f, 14f, paintRect)
            canvas.drawRoundRect(rect, 14f, 14f, paintRectStroke)
        }

        activeRect?.let { rect ->
            canvas.drawRoundRect(rect, 14f, 14f, paintRect)
            canvas.drawRoundRect(rect, 14f, 14f, paintRectStroke)
        }

        // draw watermark dot
        val wx = watermarkX
        val wy = watermarkY
        if (wx != null && wy != null) {
            canvas.drawCircle(wx * width, wy * height, 10f, paintDot)
            canvas.drawCircle(wx * width, wy * height, 18f, paintDot.apply { alpha = 80 })
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == Mode.NONE) return false
        val x = event.x
        val y = event.y
        when (mode) {
            Mode.WATERMARK -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    watermarkX = (x / max(1f, width.toFloat())).coerceIn(0f, 1f)
                    watermarkY = (y / max(1f, height.toFloat())).coerceIn(0f, 1f)
                    invalidate()
                    return true
                }
            }
            Mode.BLUR -> {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = x
                        startY = y
                        activeRect = RectF(x, y, x, y)
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        activeRect?.let { r ->
                            r.left = min(startX, x)
                            r.top = min(startY, y)
                            r.right = max(startX, x)
                            r.bottom = max(startY, y)
                            invalidate()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val r = activeRect
                        activeRect = null
                        if (r != null && (abs(r.width()) > 20f) && (abs(r.height()) > 20f)) {
                            val left = (r.left / max(1f, width.toFloat())).coerceIn(0f, 1f)
                            val top = (r.top / max(1f, height.toFloat())).coerceIn(0f, 1f)
                            val right = (r.right / max(1f, width.toFloat())).coerceIn(0f, 1f)
                            val bottom = (r.bottom / max(1f, height.toFloat())).coerceIn(0f, 1f)
                            blurRects.add(BlurRectNorm(left, top, right, bottom))
                        }
                        invalidate()
                        return true
                    }
                }
            }
            else -> Unit
        }
        return super.onTouchEvent(event)
    }
}
