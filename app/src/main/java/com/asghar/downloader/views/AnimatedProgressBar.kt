package com.asghar.downloader.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/** Compact MovieBox/VidMate-style progress bar: real progress + subtle moving sheen. */
class AnimatedProgressBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var displayed = 0f
    private var target = 0f
    private var animator: ValueAnimator? = null
    private var shimmer = 0f
    private var shimmerAnimator: ValueAnimator? = null

    init {
        isFocusable = false
        bgPaint.color = 0xFF3B3B3B.toInt()
        progressPaint.color = 0xFF20E6A1.toInt()
        startShimmer()
    }

    fun setProgress(value: Int, animate: Boolean = true) {
        val next = value.coerceIn(0, 100).toFloat()
        target = next
        animator?.cancel()
        if (!animate || kotlin.math.abs(displayed - next) < 0.5f) {
            displayed = next
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(displayed, next).apply {
            duration = 520L.coerceAtMost(900L).coerceAtLeast(220L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                displayed = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = ValueAnimator.ofFloat(-0.25f, 1.25f).apply {
            duration = 1150L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                shimmer = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val radius = h / 2f
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        if (displayed <= 0f || width <= 0) return
        val end = width * displayed / 100f
        rect.set(0f, 0f, end, h)
        val start = (shimmer - 0.18f) * end
        val stop = (shimmer + 0.18f) * end
        progressPaint.shader = LinearGradient(
            start, 0f, stop, 0f,
            intArrayOf(0xFF20E6A1.toInt(), 0xFF7CFFD2.toInt(), 0xFF20E6A1.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, progressPaint)
        progressPaint.shader = null
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        shimmerAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
