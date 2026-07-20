package com.eip.netsniffer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * First-run guided tour: spotlights key UI targets (bell, VIP/launcher FAB)
 * with a Bengali tooltip + "বুঝেছি" button. Shown once per install
 * (or after a reset via `resetForDebug`).
 */
object OnboardingCoach {
    private const val PREF = "eip_onboarding"
    private const val KEY_DONE = "coach_v1_done"

    fun maybeShow(activity: Activity, bellTarget: View, vipTarget: View) {
        val prefs = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return

        // Wait until targets are laid out.
        bellTarget.post {
            vipTarget.post {
                showStep(activity, prefs, bellTarget, vipTarget, step = 0)
            }
        }
    }

    @Suppress("unused")
    fun resetForDebug(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove(KEY_DONE).apply()
    }

    private fun showStep(
        activity: Activity,
        prefs: android.content.SharedPreferences,
        bell: View,
        vip: View,
        step: Int,
    ) {
        val root = activity.window.decorView as ViewGroup
        // Remove any leftover overlay.
        (0 until root.childCount)
            .map { root.getChildAt(it) }
            .filterIsInstance<CoachOverlay>()
            .forEach { root.removeView(it) }

        val (target, title, body) = when (step) {
            0 -> Triple(
                bell,
                "🔔 Notification",
                "এখানে আপনার সমস্ত update, alert এবং admin থেকে আসা message দেখতে পাবেন। " +
                    "নতুন কিছু এলে লাল দাগ (badge) উঠবে।"
            )
            1 -> Triple(
                vip,
                "👑 VIP Control Panel",
                "এই button-এ চাপ দিলে সব কিছু পাবেন — Aviator signal, WinGo prediction, " +
                    "Auto-Bet, Setup video সব একসাথে। এটাই আপনার main control।"
            )
            else -> {
                prefs.edit().putBoolean(KEY_DONE, true).apply()
                return
            }
        }

        val overlay = CoachOverlay(activity, target, title, body) {
            showStep(activity, prefs, bell, vip, step + 1)
        }
        root.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }
}

@SuppressLint("ViewConstructor")
private class CoachOverlay(
    context: Context,
    private val target: View,
    title: String,
    body: String,
    private val onNext: () -> Unit,
) : FrameLayout(context) {

    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC000000.toInt()
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFD54F.toInt() // gold
        strokeWidth = dp(3f)
    }
    private val ringOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55FFD54F
        strokeWidth = dp(10f)
    }

    private val loc = IntArray(2)
    private var targetRect = RectF()

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
        isFocusable = true

        val card = buildCard(title, body)
        addView(card)

        // Position card after layout.
        post { positionCard(card) }
    }

    private fun buildCard(title: String, body: String): LinearLayout {
        val ctx = context
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(18), dpi(16), dpi(18), dpi(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(16f)
                setColor(0xFF1E1B2E.toInt())
                setStroke(dpi(1), 0x66FFD54F)
            }
            elevation = dp(8f)
        }
        card.addView(TextView(ctx).apply {
            text = title
            setTextColor(0xFFFFD54F.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(ctx).apply {
            text = body
            setTextColor(0xFFECEAF6.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(2f), 1.15f)
            (layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )).also {
                it.topMargin = dpi(8)
                layoutParams = it
            }
        })
        card.addView(Button(ctx).apply {
            text = "বুঝেছি ✓"
            setTextColor(0xFF1E1B2E.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isAllCaps = false
            stateListAnimator = null
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10f)
                setColor(0xFFFFD54F.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpi(42)
            ).apply { topMargin = dpi(12) }
            setOnClickListener {
                (parent as? ViewGroup)?.removeView(this@CoachOverlay)
                onNext()
            }
        })
        card.layoutParams = LayoutParams(dpi(300), LayoutParams.WRAP_CONTENT)
        return card
    }

    private fun positionCard(card: View) {
        target.getLocationInWindow(loc)
        val myLoc = IntArray(2)
        getLocationInWindow(myLoc)
        val tx = loc[0] - myLoc[0]
        val ty = loc[1] - myLoc[1]
        val cx = tx + target.width / 2f
        val cy = ty + target.height / 2f
        val radius = (maxOf(target.width, target.height) / 2f) + dp(12f)
        targetRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        invalidate()

        // Place card: below target if room, else above.
        val screenH = height.toFloat()
        card.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        )
        val cardH = card.measuredHeight
        val cardW = card.measuredWidth
        val marginSide = dpi(16)

        val lp = card.layoutParams as LayoutParams
        val below = cy + radius + dp(16f)
        val above = cy - radius - dp(16f) - cardH
        lp.topMargin = if (below + cardH < screenH - dpi(24)) below.toInt() else above.toInt().coerceAtLeast(dpi(24))
        // Horizontal center around target but keep inside screen.
        val desiredLeft = (cx - cardW / 2f).toInt()
        val left = desiredLeft.coerceIn(marginSide, (width - cardW - marginSide).coerceAtLeast(marginSide))
        lp.leftMargin = left
        lp.gravity = Gravity.TOP or Gravity.START
        card.layoutParams = lp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        if (targetRect.width() > 0) {
            val cx = targetRect.centerX()
            val cy = targetRect.centerY()
            val r = targetRect.width() / 2f
            canvas.drawCircle(cx, cy, r, holePaint)
            canvas.drawCircle(cx, cy, r + dp(6f), ringOuterPaint)
            canvas.drawCircle(cx, cy, r, ringPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Swallow taps so nothing behind the overlay reacts.
        return true
    }

    private fun dp(v: Float): Float =
        v * resources.displayMetrics.density
    private fun dpi(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
