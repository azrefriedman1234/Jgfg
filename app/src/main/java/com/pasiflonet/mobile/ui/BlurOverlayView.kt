package com.pasiflonet.mobile.ui

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

class BlurOverlayView @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    var blurMode: Boolean = false
        set(v) { field = v; invalidate() }

    var allowRectangles: Boolean = true

    private val rects = mutableListOf<RectF>()
    private var cur: RectF? = null
    private var startX = 0f
    private var startY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 70
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        alpha = 220
    }

    fun clearAll() {
        rects.clear()
        cur = null
        invalidate()
    }

    fun exportRectsNormalized(): List<NormRect> {
        val w = max(1, width).toFloat()
        val h = max(1, height).toFloat()
        return rects.map { r ->
            val l = (r.left / w).coerceIn(0f, 1f)
            val t = (r.top / h).coerceIn(0f, 1f)
            val rr = (r.right / w).coerceIn(0f, 1f)
            val bb = (r.bottom / h).coerceIn(0f, 1f)
            NormRect(l, t, rr, bb)
        }
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (!blurMode) return

        rects.forEach { r ->
            c.drawRect(r, fill)
            c.drawRect(r, stroke)
        }
        cur?.let {
            c.drawRect(it, fill)
            c.drawRect(it, stroke)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!blurMode || !allowRectangles) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                cur = RectF(startX, startY, startX, startY)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val x = ev.x
                val y = ev.y
                cur = RectF(min(startX, x), min(startY, y), max(startX, x), max(startY, y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val r = cur
                cur = null
                if (r != null && (abs(r.width()) > 20f) && (abs(r.height()) > 20f)) {
                    rects.add(r)
                }
                invalidate()
                return true
            }
        }
        return false
    }
}
