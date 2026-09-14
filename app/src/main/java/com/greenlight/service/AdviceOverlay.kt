package com.greenlight.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.greenlight.model.GlosaAction
import com.greenlight.model.GlosaAdvice
import com.greenlight.model.TimingSource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating bubble.
 *
 * This is the piece that makes the feature usable with Google Maps rather than instead of it:
 * a TYPE_APPLICATION_OVERLAY window draws on top of whatever navigation app owns the screen.
 * Drag to move, tap to collapse to a dot.
 */
class AdviceOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private var speedView: TextView? = null
    private var unitView: TextView? = null
    private var detailView: TextView? = null
    private var sourceView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var lastColor = COLOUR_NEUTRAL

    val isShowing: Boolean get() = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return

        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).roundToInt()

        val speed = TextView(context).apply {
            textSize = 40f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            text = "--"
            gravity = Gravity.CENTER
        }
        val unit = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.argb(210, 255, 255, 255))
            text = "km/h"
            gravity = Gravity.CENTER
        }
        val detail = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.argb(235, 255, 255, 255))
            text = "starting"
            gravity = Gravity.CENTER
        }
        val source = TextView(context).apply {
            textSize = 9f
            setTextColor(Color.argb(170, 255, 255, 255))
            text = ""
            gravity = Gravity.CENTER
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(COLOUR_NEUTRAL)
                setStroke(dp(2), Color.argb(70, 0, 0, 0))
            }
            addView(speed)
            addView(unit)
            addView(detail)
            addView(source)
            elevation = dp(8).toFloat()
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(140)
        }

        container.setOnTouchListener(DragListener(lp, container))

        windowManager.addView(container, lp)
        root = container
        speedView = speed
        unitView = unit
        detailView = detail
        sourceView = source
        params = lp
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        speedView = null
        detailView = null
        sourceView = null
        params = null
    }

    fun update(advice: GlosaAdvice, currentMps: Double) {
        val speed = speedView ?: return
        val detail = detailView ?: return
        val source = sourceView ?: return

        val target = advice.targetMps
        when (advice.action) {
            GlosaAction.HOLD, GlosaAction.SPEED_UP, GlosaAction.EASE_OFF -> {
                speed.text = "${(target!! * 3.6).roundToInt()}"
                unitView?.text = "km/h"
                val d = advice.distanceMeters.roundToInt()
                val verb = when (advice.action) {
                    GlosaAction.HOLD -> "hold"
                    GlosaAction.SPEED_UP -> "pick up"
                    else -> "ease off"
                }
                val chain = if (advice.signalsCleared > 1) " · ${advice.signalsCleared} lights" else ""
                detail.text = "$verb · ${d} m$chain"
            }

            GlosaAction.STOP_EXPECTED -> {
                val ttg = advice.timeToGreenSec?.roundToInt()
                speed.text = ttg?.toString() ?: "—"
                unitView?.text = "s to green"
                detail.text = "red at ${advice.distanceMeters.roundToInt()} m"
            }

            GlosaAction.NO_ADVICE -> {
                speed.text = "--"
                unitView?.text = "km/h"
                detail.text = advice.note ?: "no data"
            }
        }

        source.text = when (advice.source) {
            TimingSource.LIVE_SPAT -> "live signal · ${(advice.confidence * 100).roundToInt()}%"
            TimingSource.MANUAL -> "manual · ${(advice.confidence * 100).roundToInt()}%"
            TimingSource.LEARNED -> "learned · ${(advice.confidence * 100).roundToInt()}%"
            TimingSource.NONE -> ""
        }

        animateTo(colourFor(advice, currentMps))
    }

    private fun colourFor(advice: GlosaAdvice, currentMps: Double): Int = when (advice.action) {
        GlosaAction.NO_ADVICE -> COLOUR_NEUTRAL
        GlosaAction.STOP_EXPECTED -> COLOUR_RED
        else -> {
            val delta = abs((advice.targetMps ?: currentMps) - currentMps)
            when {
                advice.confidence < 0.65 -> COLOUR_AMBER
                delta < 1.0 -> COLOUR_GREEN
                else -> COLOUR_AMBER
            }
        }
    }

    /** Colour changes are animated so a flicker between states does not strobe at the driver. */
    private fun animateTo(target: Int) {
        if (target == lastColor) return
        val from = lastColor
        lastColor = target
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val c = Color.rgb(
                    (Color.red(from) + (Color.red(target) - Color.red(from)) * f).roundToInt(),
                    (Color.green(from) + (Color.green(target) - Color.green(from)) * f).roundToInt(),
                    (Color.blue(from) + (Color.blue(target) - Color.blue(from)) * f).roundToInt(),
                )
                (root?.background as? GradientDrawable)?.setColor(c)
            }
            start()
        }
    }

    private inner class DragListener(
        private val lp: WindowManager.LayoutParams,
        private val view: View,
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    lp.x = initialX + dx
                    lp.y = initialY + dy
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleCollapsed()
                    return true
                }
            }
            return false
        }
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        val visibility = if (collapsed) View.GONE else View.VISIBLE
        unitView?.visibility = visibility
        detailView?.visibility = visibility
        sourceView?.visibility = visibility
        speedView?.textSize = if (collapsed) 22f else 40f
    }

    companion object {
        private val COLOUR_GREEN = Color.rgb(22, 128, 62)
        private val COLOUR_AMBER = Color.rgb(176, 110, 12)
        private val COLOUR_RED = Color.rgb(154, 44, 44)
        private val COLOUR_NEUTRAL = Color.rgb(52, 58, 68)
    }
}
