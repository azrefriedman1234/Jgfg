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

    var blurMode: Boolean = false
    var allowRectangles: Boolean = false

    private val rects = mutableListOf<RectF>()
    private var current: RectF? = null
    private var downX = 0f
    private var downY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 60
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        alpha = 180
    }

    // תאימות לשם הישן שהשתרבב אצלך
    fun setDrawEnabled(enabled: Boolean) {
        allowRectangles = enabled
        invalidate()
    }

    fun clearAll() {
        rects.clear()
        current = null
        invalidate()
    }

    fun exportRectsNormalized(): List<RectF> {
        val w = max(1f, width.toFloat())
        val h = max(1f, height.toFloat())
        return rects.map {
            RectF(
                (it.left / w).coerceIn(0f, 1f),
                (it.top / h).coerceIn(0f, 1f),
                (it.right / w).coerceIn(0f, 1f),
                (it.bottom / h).coerceIn(0f, 1f)
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!blurMode) return

        rects.forEach {
            canvas.drawRect(it, fill)
            canvas.drawRect(it, stroke)
        }
        current?.let {
            canvas.drawRect(it, fill)
            canvas.drawRect(it, stroke)
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!blurMode || !allowRectangles) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                current = RectF(downX, downY, downX, downY)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val r = current ?: return true
                r.left = min(downX, ev.x)
                r.top = min(downY, ev.y)
                r.right = max(downX, ev.x)
                r.bottom = max(downY, ev.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val r = current
                current = null
                if (r != null) {
                    val minSize = 24f
                    if (abs(r.width()) >= minSize && abs(r.height()) >= minSize) {
                        rects.add(r)
                    }
                }
                invalidate()
                return true
            }
        }
        return false
    }
}
