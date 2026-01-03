package com.pasiflonet.mobile.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BlurOverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var blurMode: Boolean = false
    var enabledForImage: Boolean = false

    var onRectFinished: ((RectF) -> Unit)? = null

    private val rects = mutableListOf<RectF>()
    private var downX = 0f
    private var downY = 0f
    private var curRect: RectF? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(220, 0, 200, 255)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(60, 0, 200, 255)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (r in rects) {
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, strokePaint)
        }
        curRect?.let { r ->
            canvas.drawRect(r, fillPaint)
            canvas.drawRect(r, strokePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabledForImage || !blurMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                curRect = RectF(downX, downY, downX, downY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                curRect?.let {
                    it.right = event.x
                    it.bottom = event.y
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val r = curRect ?: return true
                curRect = null

                val norm = RectF(
                    min(r.left, r.right),
                    min(r.top, r.bottom),
                    max(r.left, r.right),
                    max(r.top, r.bottom)
                )

                if (abs(norm.width()) > 12 && abs(norm.height()) > 12) {
                    rects.add(norm)
                    onRectFinished?.invoke(norm)
                }
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun clearAll() {
        rects.clear()
        curRect = null
        invalidate()
    }
}
