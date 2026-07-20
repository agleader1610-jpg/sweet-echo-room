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
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Second-layer lock: shown on Aviator / WinGo / WinGo AutoBet when the
 * user is a valid team member (LicenseGate OK) but does NOT meet the
 * current deposit rule. Browser tab is intentionally NOT locked so the
 * user can go deposit or open their Recharge History to unlock.
 */
object DepositLockOverlay {

    private const val RECHARGE_URL =
        "https://dkwin8.com/#/wallet/RechargeHistory"
    private const val DEPOSIT_URL =
        "https://dkwin8.com/#/wallet/Deposit"

    fun wrap(fragment: Fragment, content: View): View {
        val ctx = content.context
        val fl = FrameLayout(ctx)
        fl.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val overlay = buildOverlay(ctx, fragment).apply { visibility = View.GONE }
        fl.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        fun apply() {
            // Only show if the license gate is happy — otherwise LicenseLockOverlay owns the screen.
            val licenseLocked = LicenseGate.isLocked()
            val depositLocked = DepositGate.isLocked()
            overlay.visibility = if (!licenseLocked && depositLocked) View.VISIBLE else View.GONE
            if (overlay.visibility == View.VISIBLE) {
                (overlay.tag as? (String) -> Unit)?.invoke(DepositGate.currentMessage())
            }
        }
        LicenseGate.state.observe(fragment.viewLifecycleOwner) { apply() }
        DepositGate.state.observe(fragment.viewLifecycleOwner) { apply() }
        apply()
        return fl
    }

    private fun buildOverlay(ctx: Context, fragment: Fragment): View {
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(0xF20A0F1A.toInt())
            isClickable = true; isFocusable = true
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setPadding(dp(ctx, 18), dp(ctx, 24), dp(ctx, 18), dp(ctx, 24))
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 22), dp(ctx, 26), dp(ctx, 22), dp(ctx, 26))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 22).toFloat()
                colors = intArrayOf(0xFF0B1120.toInt(), 0xFF11162B.toInt())
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(dp(ctx, 1), 0xFFF59E0B.toInt())
            }
        }
        card.addView(TextView(ctx).apply {
            text = "💰"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            gravity = Gravity.CENTER
        })
        card.addView(TextView(ctx).apply {
            text = "DEPOSIT REQUIRED"
            setTextColor(0xFFF59E0B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
        })
        card.addView(TextView(ctx).apply {
            text = "Signal ব্যবহারের জন্য deposit প্রয়োজন"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(ctx, 16))
        })
        val messageView = TextView(ctx).apply {
            text = DepositGate.currentMessage()
            setTextColor(0xFFFDE68A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(ctx, 4).toFloat(), 1f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(0x22F59E0B.toInt())
                setStroke(dp(ctx, 1), 0x66F59E0B.toInt())
            }
        }
        card.addView(messageView)
        // Save updater lambda on tag so `apply()` can refresh copy live.
        root.tag = { msg: String -> messageView.text = msg }

        card.addView(Button(ctx).apply {
            text = "💵  DEPOSIT NOW"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            letterSpacing = 0.15f
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                colors = intArrayOf(0xFF16A34A.toInt(), 0xFF059669.toInt())
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14))
            setOnClickListener { openInBrowser(ctx, DEPOSIT_URL) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 18) })

        card.addView(Button(ctx).apply {
            text = "📜  OPEN RECHARGE HISTORY"
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(0x2222D3EE.toInt())
                setStroke(dp(ctx, 1), 0x6622D3EE.toInt())
            }
            setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
            setOnClickListener { openInBrowser(ctx, RECHARGE_URL) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 10) })

        card.addView(Button(ctx).apply {
            text = "🔄  RECHECK"
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(0x22FFFFFF.toInt())
                setStroke(dp(ctx, 1), 0x33FFFFFF.toInt())
            }
            setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
            setOnClickListener { DepositGate.refresh(ctx) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 10) })

        scroll.addView(card, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        return root
    }

    private fun openInBrowser(ctx: Context, url: String) {
        try {
            var c: Context? = ctx
            while (c is android.content.ContextWrapper && c !is MainActivity) c = c.baseContext
            val act = c as? MainActivity ?: return
            act.switchToBrowserTab()
            act.window.decorView.post {
                (act.supportFragmentManager.findFragmentByTag("f0") as? BrowserFragment)?.navigateTo(url)
            }
        } catch (_: Throwable) {}
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
