package com.pasiflonet.mobile.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BlurOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var blurMode: Boolean = false
        set(v) { field = v; invalidate() }

    var allowRectangles: Boolean = true

    private val rects = ArrayList<RectF>()
    private var cur: RectF? = null
    private var downX = 0f
    private var downY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(70, 0, 0, 0)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(220, 0, 180, 255)
    }

    fun setEnabledForImage(enabled: Boolean) {
        isEnabled = enabled
        allowRectangles = enabled
        blurMode = false
        invalidate()
    }

    fun clearAll() {
        rects.clear()
        cur = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (r in rects) {
            canvas.drawRect(r, fill)
            canvas.drawRect(r, stroke)
        }
        cur?.let {
            canvas.drawRect(it, fill)
            canvas.drawRect(it, stroke)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isEnabled || !blurMode || !allowRectangles) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                cur = RectF(downX, downY, downX, downY)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val r = cur ?: return true
                r.left = min(downX, ev.x)
                r.top = min(downY, ev.y)
                r.right = max(downX, ev.x)
                r.bottom = max(downY, ev.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val r = cur
                cur = null
                parent?.requestDisallowInterceptTouchEvent(false)

                if (r != null) {
                    if (abs(r.width()) >= 12f && abs(r.height()) >= 12f) {
                        rects.add(r)
                    }
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    fun exportRectsNormalized(): List<RectF> {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        return rects.map {
            RectF(
                (it.left / w).coerceIn(0f, 1f),
                (it.top / h).coerceIn(0f, 1f),
                (it.right / w).coerceIn(0f, 1f),
                (it.bottom / h).coerceIn(0f, 1f)
            )
        }
    }
}
