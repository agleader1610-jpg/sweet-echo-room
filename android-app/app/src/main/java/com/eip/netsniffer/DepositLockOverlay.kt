package com.eip.netsniffer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Premium deposit-required overlay. Message body is driven entirely by the
 * server (see check-deposit-gate.ts) so the copy stays perfectly in sync
 * with the current gate state (fresh user → progress → recency-only).
 */
object DepositLockOverlay {

    private const val RECHARGE_URL =
        "https://dkwin8.com/#/wallet/RechargeHistory"
    private const val DEPOSIT_URL =
        "https://dkwin8.com/#/wallet/Recharge"

    private fun gameLabel(game: String): String = when (game) {
        DepositGate.GAME_WINGO -> "WinGo Signal"
        DepositGate.GAME_WINGO_AUTO -> "WinGo AutoBet"
        else -> "Aviator Signal"
    }

    fun wrap(fragment: Fragment, content: View, game: String = DepositGate.GAME_AVIATOR): View {
        val ctx = content.context
        val fl = FrameLayout(ctx)
        fl.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val overlay = buildOverlay(ctx, fragment, game).apply { visibility = View.GONE }
        fl.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        fun apply() {
            val licenseLocked = LicenseGate.isLocked()
            val depositLocked = DepositGate.isLocked(game)
            val show = !licenseLocked && depositLocked
            overlay.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                @Suppress("UNCHECKED_CAST")
                (overlay.tag as? (String) -> Unit)?.invoke(DepositGate.currentMessage(game))
            }
        }
        LicenseGate.state.observe(fragment.viewLifecycleOwner) { apply() }
        DepositGate.stateFor(game).observe(fragment.viewLifecycleOwner) { apply() }
        apply()
        return fl
    }

    private fun buildOverlay(ctx: Context, fragment: Fragment, game: String): View {
        val root = FrameLayout(ctx).apply {
            // Deep translucent scrim with subtle blueish tint.
            setBackgroundColor(0xF5060814.toInt())
            isClickable = true; isFocusable = true
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setPadding(dp(ctx, 20), dp(ctx, 28), dp(ctx, 20), dp(ctx, 28))
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 22), dp(ctx, 28), dp(ctx, 22), dp(ctx, 24))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 24).toFloat()
                colors = intArrayOf(0xFF0B1120.toInt(), 0xFF0E1730.toInt(), 0xFF11162B.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(ctx, 1), 0x66F59E0B.toInt())
            }
            elevation = dp(ctx, 12).toFloat()
        }

        // Top pill badge
        card.addView(TextView(ctx).apply {
            text = "⚡ PREMIUM ACCESS"
            setTextColor(0xFFFCD34D.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.35f
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 40).toFloat()
                setColor(0x33F59E0B.toInt())
                setStroke(dp(ctx, 1), 0x66F59E0B.toInt())
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // Glowing coin icon
        val icon = TextView(ctx).apply {
            text = "💎"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 68f)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 14), 0, dp(ctx, 4))
        }
        card.addView(icon)
        // Gentle pulse animation on the icon.
        icon.animate()
            .scaleX(1.08f).scaleY(1.08f)
            .setDuration(1400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction(object : Runnable {
                override fun run() {
                    icon.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(1400)
                        .withEndAction {
                            icon.animate().scaleX(1.08f).scaleY(1.08f)
                                .setDuration(1400).withEndAction(this).start()
                        }.start()
                }
            }).start()

        // Game name — first prominent label
        card.addView(TextView(ctx).apply {
            text = gameLabel(game).uppercase()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 4))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        // Divider line
        card.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                colors = intArrayOf(0x00F59E0B, 0xAAF59E0B.toInt(), 0x00F59E0B)
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
        }, LinearLayout.LayoutParams(
            dp(ctx, 80), dp(ctx, 1),
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(ctx, 8); bottomMargin = dp(ctx, 14)
        })

        // Server-driven message
        val messageView = TextView(ctx).apply {
            text = DepositGate.currentMessage(game)
            setTextColor(0xFFF1F5F9.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(ctx, 4).toFloat(), 1.15f)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                setColor(0x14FFFFFF)
                setStroke(dp(ctx, 1), 0x22FFFFFF)
            }
        }
        card.addView(messageView)
        root.tag = { msg: String -> messageView.text = msg }

        // Premium DEPOSIT NOW button — layered gradient + shine ring
        val depositWrap = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                colors = intArrayOf(0xFF22C55E.toInt(), 0xFF16A34A.toInt(), 0xFF047857.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(ctx, 1), 0x88FFFFFF.toInt())
            }
            foreground = RippleDrawable(
                ColorStateList.valueOf(0x44FFFFFF), null, null,
            )
            elevation = dp(ctx, 6).toFloat()
            isClickable = true; isFocusable = true
            setOnClickListener { openInBrowser(ctx, DEPOSIT_URL) }
        }
        val depositRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 22), dp(ctx, 16), dp(ctx, 22), dp(ctx, 16))
        }
        depositRow.addView(TextView(ctx).apply {
            text = "💵"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, 0, dp(ctx, 10), 0)
        })
        depositRow.addView(TextView(ctx).apply {
            text = "DEPOSIT NOW"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            letterSpacing = 0.2f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        depositWrap.addView(depositRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        card.addView(depositWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 20) })

        // Secondary — Recharge History
        card.addView(makeGhost(ctx, "📜  RECHARGE HISTORY", 0xFF60A5FA.toInt()) {
            openInBrowser(ctx, RECHARGE_URL)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 12) })

        // Tertiary — Recheck
        card.addView(makeGhost(ctx, "🔄  RECHECK STATUS", 0xFF94A3B8.toInt()) {
            DepositGate.refresh(ctx)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 8) })

        // Footer hint
        card.addView(TextView(ctx).apply {
            text = "🔒 Secure • Instant Detect • Auto Unlock"
            setTextColor(0xFF64748B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
            setPadding(0, dp(ctx, 18), 0, 0)
        })

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

    private fun makeGhost(ctx: Context, label: String, tint: Int, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            setTextColor(tint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            setPadding(dp(ctx, 18), dp(ctx, 12), dp(ctx, 18), dp(ctx, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(0x14FFFFFF)
                setStroke(dp(ctx, 1), (tint and 0x00FFFFFF) or 0x55000000)
            }
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
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
