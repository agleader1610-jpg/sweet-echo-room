package com.eip.netsniffer

import android.app.Activity
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
 * In-app review reminder. Shows once when the user opens the Aviator or
 * WinGo panel. A "বুঝেছি" button dismisses it. No auto-hide, no cooldown,
 * no other trigger points.
 */
object ReviewNagger {
    private const val TAG = "eip_review_nagger_tag"

    private const val TITLE = "📝 রিভিউ জমা দিন"
    private const val BODY =
        "আপনার গেম হিস্টোরি থেকে কেমন Profit / Loss হয়েছে সেটার রিভিউ অবশ্যই জমা দিন। " +
        "নয়তো আপনার account lock করে দেওয়া হতে পারে।"

    fun show(activity: Activity?) {
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        val root = activity.window?.decorView as? ViewGroup ?: return
        // Already visible → don't stack duplicates.
        if (root.findViewWithTag<View>(TAG) != null) return

        val ctx = activity
        val dp = ctx.resources.displayMetrics.density

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16*dp).toInt(),(14*dp).toInt(),(16*dp).toInt(),(14*dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * dp
                colors = intArrayOf(Color.parseColor("#0F172A"), Color.parseColor("#1E293B"))
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke((1.5f * dp).toInt(), Color.parseColor("#F5C542"))
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
            setPadding(0,(4*dp).toInt(),0,(10*dp).toInt())
        })

        val btn = TextView(ctx).apply {
            text = "বুঝেছি"
            setTextColor(Color.parseColor("#0F172A"))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding((22*dp).toInt(),(9*dp).toInt(),(22*dp).toInt(),(9*dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * dp
                colors = intArrayOf(Color.parseColor("#FFD54F"), Color.parseColor("#F5C542"))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
        }
        val btnLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.END }
        card.addView(btn, btnLp)

        val wrap = FrameLayout(ctx).apply {
            tag = TAG
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
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

        btn.setOnClickListener { dismiss(wrap) }
    }

    fun show(fragment: Fragment?) = show(fragment?.activity)

    // One-shot dedupe so review shows at most once per fragment instance.
    private val shownFor = java.util.WeakHashMap<Fragment, Boolean>()

    /**
     * Show review reminder ONLY when the given tab is fully unlocked
     * (LicenseGate = LICENSED  AND  DepositGate allowed for this game).
     * Fires at most once per fragment instance. Safe to call from onViewCreated.
     */
    fun showWhenUnlocked(fragment: Fragment, game: String) {
        if (shownFor.containsKey(fragment)) return
        val tryShow = tryShow@ {
            if (shownFor.containsKey(fragment)) return@tryShow
            val act = fragment.activity ?: return@tryShow
            if (act.isFinishing || act.isDestroyed) return@tryShow
            val licensed = LicenseGate.state.value?.status == "LICENSED"
            val depositOk = !DepositGate.isLocked(game)
            if (licensed && depositOk) {
                shownFor[fragment] = true
                show(act)
            }
        }
        LicenseGate.state.observe(fragment.viewLifecycleOwner) { tryShow() }
        DepositGate.stateFor(game).observe(fragment.viewLifecycleOwner) { tryShow() }
        fragment.view?.postDelayed({ tryShow() }, 600)
    }

    private fun dismiss(wrap: View) {
        val dp = wrap.resources.displayMetrics.density
        wrap.animate().alpha(0f).translationY(-30f * dp)
            .setDuration(220)
            .withEndAction { (wrap.parent as? ViewGroup)?.removeView(wrap) }
            .start()
    }
}

