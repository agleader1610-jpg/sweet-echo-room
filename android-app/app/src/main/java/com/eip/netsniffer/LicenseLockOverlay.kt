package com.eip.netsniffer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Wraps a fragment's content in a FrameLayout with a full-screen lock overlay
 * that becomes visible whenever LicenseGate reports a LOCKED / UNKNOWN state.
 */
object LicenseLockOverlay {

    fun wrap(fragment: Fragment, content: View): View {
        val ctx = content.context
        val fl = FrameLayout(ctx)
        (content.layoutParams as? ViewGroup.LayoutParams)?.let { content.layoutParams = it }
        fl.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val overlay = buildOverlay(ctx)
        overlay.visibility = View.GONE
        fl.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val msgView = overlay.findViewWithTag<TextView>("license_msg")
        LicenseGate.state.observe(fragment.viewLifecycleOwner) { st ->
            val locked = st == null || st.locked
            overlay.visibility = if (locked) View.VISIBLE else View.GONE
            if (locked) msgView?.text = LicenseGate.currentMessage()
        }
        // If we're already locked at attach time, ensure UI reflects that.
        if (LicenseGate.isLocked()) {
            overlay.visibility = View.VISIBLE
            msgView?.text = LicenseGate.currentMessage()
        }
        return fl
    }

    private fun buildOverlay(ctx: Context): View {
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(0xF20A0F1A.toInt())
            isClickable = true
            isFocusable = true
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 24), dp(ctx, 28), dp(ctx, 24), dp(ctx, 28))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 20).toFloat()
                setColor(0xFF0F172A.toInt())
                setStroke(dp(ctx, 2), 0xFFEF4444.toInt())
            }
        }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(ctx, 20); rightMargin = dp(ctx, 20)
        }

        card.addView(TextView(ctx).apply {
            text = "🔒"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            gravity = Gravity.CENTER
        })
        card.addView(TextView(ctx).apply {
            text = "LOCKED"
            setTextColor(0xFFEF4444.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 6))
        })
        card.addView(TextView(ctx).apply {
            text = "EIP TEAM • Signal Access"
            setTextColor(0xFF94A3B8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        })
        card.addView(TextView(ctx).apply {
            tag = "license_msg"
            text = LicenseGate.currentMessage()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 4), dp(ctx, 18), dp(ctx, 4), dp(ctx, 18))
            setLineSpacing(dp(ctx, 4).toFloat(), 1f)
        })
        card.addView(Button(ctx).apply {
            text = "🔄 RECHECK LOGIN"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(0xFF6D28D9.toInt())
            }
            setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
            setOnClickListener { LicenseGate.refresh(ctx) }
        })
        root.addView(card, lp)
        return root
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
