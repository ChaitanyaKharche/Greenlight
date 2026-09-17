package com.greenlight.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.greenlight.core.UnitSystem
import com.greenlight.core.formatDistance
import com.greenlight.model.GlosaAction
import com.greenlight.model.GlosaAdvice
import com.greenlight.model.TimingSource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating bubble.
 *
 * This is what makes the feature usable alongside Google Maps rather than instead of it: a
 * TYPE_APPLICATION_OVERLAY window draws over whichever navigation app owns the screen.
 *
 * Layout is driven by what a driver can absorb in a glance. One large number is the thing to
 * act on. Under it, the posted limit and the current speed sit side by side, because the
 * useful judgement is always a comparison - am I above the limit, and how far is the target
 * from what I am doing. Anything finer goes on the last line, to be read at a standstill.
 */
class AdviceOverlay(
    private val context: Context,
    private var units: UnitSystem,
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private var bigValue: TextView? = null
    private var bigCaption: TextView? = null
    private var limitValue: TextView? = null
    private var nowValue: TextView? = null
    private var nowCaption: TextView? = null
    private var columns: LinearLayout? = null
    private var detailView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed = false
    private var lastColor = COLOUR_NEUTRAL

    val isShowing: Boolean get() = root != null

    fun setUnits(newUnits: UnitSystem) {
        units = newUnits
    }

    private fun density() = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density()).roundToInt()

    /** One labelled column of the limit/now pair. */
    private fun statColumn(caption: String): Pair<LinearLayout, Pair<TextView, TextView>> {
        val label = TextView(context).apply {
            textSize = 9f
            setTextColor(Color.argb(160, 255, 255, 255))
            text = caption
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }
        val value = TextView(context).apply {
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            text = "—"
            gravity = Gravity.CENTER
        }
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(9), 0, dp(9), 0)
            addView(label)
            addView(value)
        }
        return col to (label to value)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return

        val big = TextView(context).apply {
            textSize = 42f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            text = "—"
            gravity = Gravity.CENTER
        }
        val caption = TextView(context).apply {
            textSize = 10f
            setTextColor(Color.argb(215, 255, 255, 255))
            text = units.label
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
        }

        val (limitCol, limitPair) = statColumn("LIMIT")
        val (nowCol, nowPair) = statColumn("NOW")
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(26))
            setBackgroundColor(Color.argb(60, 255, 255, 255))
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
            addView(limitCol)
            addView(divider)
            addView(nowCol)
        }

        val detail = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.argb(225, 255, 255, 255))
            text = "starting"
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(15), dp(11), dp(15), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(COLOUR_NEUTRAL)
                setStroke(dp(2), Color.argb(70, 0, 0, 0))
            }
            addView(big)
            addView(caption)
            addView(row)
            addView(detail)
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
        bigValue = big
        bigCaption = caption
        limitValue = limitPair.second
        nowValue = nowPair.second
        nowCaption = nowPair.first
        columns = row
        detailView = detail
        params = lp
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        bigValue = null
        bigCaption = null
        limitValue = null
        nowValue = null
        nowCaption = null
        columns = null
        detailView = null
        params = null
    }

    fun update(advice: GlosaAdvice, currentMps: Double) {
        val big = bigValue ?: return
        val caption = bigCaption ?: return
        val detail = detailView ?: return

        // The limit is shown whenever we know it, advice or not.
        limitValue?.text = units.format(advice.speedLimitMps)
        nowValue?.text = units.display(currentMps).toString()

        when (advice.action) {
            GlosaAction.HOLD, GlosaAction.SPEED_UP, GlosaAction.EASE_OFF -> {
                val target = advice.targetMps!!
                big.text = units.display(target).toString()
                caption.text = "${units.label} target"
                nowCaption?.text = "NOW"
                val chain = if (advice.signalsCleared > 1) {
                    " · clears ${advice.signalsCleared}"
                } else ""
                detail.text = verb(advice.action) + " · " +
                    formatDistance(advice.distanceMeters, units) + chain
            }

            GlosaAction.STOP_EXPECTED -> {
                big.text = advice.timeToGreenSec?.roundToInt()?.toString() ?: "—"
                caption.text = "s to green"
                nowCaption?.text = "NOW"
                detail.text = "red at " + formatDistance(advice.distanceMeters, units)
            }

            GlosaAction.NO_ADVICE -> {
                // With no advice the large number becomes the speedometer, which is at least
                // honest and useful. A permanent dash reads as a broken app.
                big.text = units.display(currentMps).toString()
                caption.text = "${units.label} now"
                nowCaption?.text = "AHEAD"
                nowValue?.text = if (advice.distanceMeters.isNaN()) "—"
                else formatDistance(advice.distanceMeters, units)
                detail.text = advice.note ?: "no data"
            }
        }

        if (advice.action != GlosaAction.NO_ADVICE && advice.source != TimingSource.NONE) {
            detail.append(" · ${(advice.confidence * 100).roundToInt()}%")
        }

        animateTo(colourFor(advice, currentMps))
    }

    private fun verb(action: GlosaAction) = when (action) {
        GlosaAction.HOLD -> "hold"
        GlosaAction.SPEED_UP -> "pick up"
        GlosaAction.EASE_OFF -> "ease off"
        else -> ""
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

    /** Tap shrinks the bubble to just the number, for when the screen is needed for the map. */
    private fun toggleCollapsed() {
        collapsed = !collapsed
        val visibility = if (collapsed) View.GONE else View.VISIBLE
        bigCaption?.visibility = visibility
        columns?.visibility = visibility
        detailView?.visibility = visibility
        bigValue?.textSize = if (collapsed) 24f else 42f
    }

    companion object {
        private val COLOUR_GREEN = Color.rgb(22, 128, 62)
        private val COLOUR_AMBER = Color.rgb(176, 110, 12)
        private val COLOUR_RED = Color.rgb(154, 44, 44)
        private val COLOUR_NEUTRAL = Color.rgb(52, 58, 68)
    }
}
