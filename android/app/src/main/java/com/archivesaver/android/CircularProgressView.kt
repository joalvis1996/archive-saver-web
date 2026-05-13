package com.archivesaver.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f
        color = ContextCompat.getColor(context, R.color.border_subtle)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f
        color = ContextCompat.getColor(context, R.color.accent)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.accent)
    }

    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3f
        color = ContextCompat.getColor(context, android.R.color.white)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 4f
        val size = width.coerceAtMost(height).toFloat()
        val left = (width - size) / 2f + inset
        val top = (height - size) / 2f + inset
        val right = left + size - inset * 2
        val bottom = top + size - inset * 2
        if (progress >= 100) {
            val centerX = (left + right) / 2f
            val centerY = (top + bottom) / 2f
            val radius = (right - left) / 2f
            canvas.drawCircle(centerX, centerY, radius, fillPaint)
            canvas.drawLine(centerX - radius * 0.42f, centerY, centerX - radius * 0.12f, centerY + radius * 0.28f, checkPaint)
            canvas.drawLine(centerX - radius * 0.12f, centerY + radius * 0.28f, centerX + radius * 0.48f, centerY - radius * 0.32f, checkPaint)
            return
        }
        canvas.drawArc(left, top, right, bottom, -90f, 360f, false, trackPaint)
        canvas.drawArc(left, top, right, bottom, -90f, 360f * progress / 100f, false, progressPaint)
    }
}
