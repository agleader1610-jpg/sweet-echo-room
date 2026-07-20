package com.eip.netsniffer

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * In-app review reminder. Slides a small premium banner from the top
 * telling the user to submit a review of their profit/loss, or their
 * account may be locked. Throttled so it doesn't spam.
 */
object ReviewNagger {
    private const val PREFS = "eip_review_nag"
    private const val KEY_LAST = "last_shown_at"
    // Show at most once every 20 minutes.
    private const val COOLDOWN_MS = 20 * 60 * 1000L

    private const val TITLE = "📝 রিভিউ জমা দিন"
    private const val BODY =
        "আপনার গেম হিস্টোরি থেকে কেমন Profit / Loss হয়েছে সেটার রিভিউ অবশ্যই জমা দিন। " +
        "নয়তো আপনার account lock করে দেওয়া হতে পারে।"

    fun maybeShow(activity: Activity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST, 0L) < COOLDOWN_MS) return
        prefs.edit().putLong(KEY_LAST, now).apply()
        show(activity)
    }

    fun maybeShow(fragment: Fragment?) = maybeShow(fragment?.activity)

    private fun show(activity: Activity) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        val ctx = activity
        val dp = ctx.resources.displayMetrics.density

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*dp).toInt(),(12*dp).toInt(),(16*dp).toInt(),(12*dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * dp
                colors = intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#1E293B"))
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke((int1dp(dp)), Color.parseColor("#F5C542"))
            }
            elevation = 16f * dp
        }
        card.addView(TextView(ctx).apply {
            text = TITLE
            setTextColor(Color.parseColor("#F5C542"))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        card.addView(TextView(ctx).apply {
            text = BODY
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0,(4*dp).toInt(),0,0)
        })

        val wrap = FrameLayout(ctx).apply {
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            layoutParams = lp
            setPadding((14*dp).toInt(),(90*dp).toInt(),(14*dp).toInt(),0)
        }
        val cardLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.TOP }
        wrap.addView(card, cardLp)
        root.addView(wrap)

        wrap.alpha = 0f
        wrap.translationY = -30f * dp
        wrap.animate().alpha(1f).translationY(0f).setDuration(280).start()

        card.setOnClickListener { dismiss(root, wrap) }
        wrap.postDelayed({ dismiss(root, wrap) }, 6500)
    }

    private fun dismiss(root: ViewGroup, wrap: View) {
        wrap.animate().alpha(0f).translationY(-30f * root.resources.displayMetrics.density)
            .setDuration(220)
            .withEndAction { (wrap.parent as? ViewGroup)?.removeView(wrap) }
            .start()
    }

    private fun int1dp(dp: Float) = (1.5f * dp).toInt()
}
