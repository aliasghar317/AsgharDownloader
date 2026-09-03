package com.asghar.downloader.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class GradientTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val text: String
    private val baseColor: Int

    private val paintBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        textSize = 18f * resources.displayMetrics.scaledDensity
    }
    private val paintGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        textSize = 18f * resources.displayMetrics.scaledDensity
    }
    private val paintLightning = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        textSize = 18f * resources.displayMetrics.scaledDensity
    }

    private var phaseSlow = 0f
    private var phaseFast = 0f
    private var boltAlpha = 0f

    private val slowAnimator: ValueAnimator
    private val fastAnimator: ValueAnimator
    private val boltAnimator: ValueAnimator

    init {
        text = "ASGHAR DOWNLOADER"
        baseColor = 0xFF6D28D9.toInt()

        paintBase.color = baseColor
        paintGlow.color = 0xFFEC4899.toInt()
        paintGlow.maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.OUTER)
        paintLightning.color = Color.WHITE
        paintLightning.maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)

        slowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                phaseSlow = it.animatedValue as Float
                invalidate()
            }
        }

        fastAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                phaseFast = it.animatedValue as Float
                invalidate()
            }
        }

        boltAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                boltAlpha = if (v < 0.15f) v / 0.15f
                else if (v < 0.35f) 1f
                else 1f - ((v - 0.35f) / 0.65f)
                invalidate()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!slowAnimator.isStarted) slowAnimator.start()
        if (!fastAnimator.isStarted) fastAnimator.start()
        if (!boltAnimator.isStarted) boltAnimator.start()
    }

    override fun onDetachedFromWindow() {
        slowAnimator.cancel()
        fastAnimator.cancel()
        boltAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val textWidth = paintBase.measureText(text)
        val cx = width / 2f
        val cy = height / 2f + paintBase.textSize / 3f

        val gradientWidth = textWidth * 0.6f
        val slowOffset = (phaseSlow * (textWidth + gradientWidth)) - gradientWidth
        val slowShader = LinearGradient(
            cx - textWidth / 2f + slowOffset, cy,
            cx - textWidth / 2f + slowOffset + gradientWidth, cy,
            intArrayOf(0xFF38BDF8.toInt(), baseColor, 0xFFEC4899.toInt(), baseColor, 0xFF38BDF8.toInt()),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        paintBase.shader = slowShader
        canvas.drawText(text, cx, cy, paintBase)
        paintBase.shader = null

        paintGlow.alpha = (180f * (0.4f + 0.6f * kotlin.math.abs(kotlin.math.sin(phaseSlow * 6.28f)))).toInt()
        canvas.drawText(text, cx, cy, paintGlow)

        val fastWidth = textWidth * 0.4f
        val fastOffset = (phaseFast * (textWidth + fastWidth)) - fastWidth
        val fastShader = LinearGradient(
            cx - textWidth / 2f + fastOffset, cy,
            cx - textWidth / 2f + fastOffset + fastWidth, cy,
            intArrayOf(Color.TRANSPARENT, 0xFFFACC15.toInt(), Color.WHITE, 0xFFFACC15.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        paintLightning.shader = fastShader
        paintLightning.alpha = (255f * boltAlpha).toInt()
        canvas.drawText(text, cx, cy, paintLightning)
        paintLightning.shader = null
    }
}
