package com.eip.netsniffer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * Premium deposit-required overlay.
 *
 * Message body is server-driven (see check-deposit-gate.ts) so the copy
 * stays perfectly in sync with the current gate state (fresh user →
 * progress → recency-only).
 *
 * "REFRESH DEPOSIT STATUS" is the smart action — it silently opens the DKWin
 * Recharge History page in the browser tab so the site's own API fires. The
 * DepositGate captures those records from the network layer and re-checks
 * the gate automatically; no direct API calls needed.
 */
object DepositLockOverlay {

    private const val RECHARGE_URL =
        "https://dkwin8.com/#/wallet/RechargeHistory"
    private const val DEPOSIT_URL =
        "https://dkwin8.com/#/wallet/Recharge"

    // Colors — luxurious champagne-gold on midnight navy.
    private const val C_GOLD = 0xFFFCD34D.toInt()
    private const val C_GOLD_DEEP = 0xFFB45309.toInt()
    private const val C_GREEN_1 = 0xFF34D399.toInt()
    private const val C_GREEN_2 = 0xFF10B981.toInt()
    private const val C_GREEN_3 = 0xFF047857.toInt()
    private const val C_BLUE = 0xFF60A5FA.toInt()
    private const val C_SLATE = 0xFFCBD5E1.toInt()
    private const val C_MUTED = 0xFF64748B.toInt()

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
            // Deep translucent scrim — near-opaque midnight for premium focus.
            setBackgroundColor(0xF7050814.toInt())
            isClickable = true; isFocusable = true
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setPadding(dp(ctx, 18), dp(ctx, 24), dp(ctx, 18), dp(ctx, 24))
        }

        // Outer card with a champagne-gold glow ring using a LayerDrawable.
        val cardBg = LayerDrawable(arrayOf(
            // Glow ring
            GradientDrawable().apply {
                cornerRadius = dp(ctx, 28).toFloat()
                colors = intArrayOf(0x66FCD34D.toInt(), 0x33F59E0B.toInt(), 0x66FCD34D.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
            },
            // Main body
            GradientDrawable().apply {
                cornerRadius = dp(ctx, 26).toFloat()
                colors = intArrayOf(0xFF0A1024.toInt(), 0xFF0F1730.toInt(), 0xFF0A0F22.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
            },
        )).apply {
            setLayerInset(1, dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2))
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 22), dp(ctx, 26), dp(ctx, 22), dp(ctx, 22))
            background = cardBg
            elevation = dp(ctx, 18).toFloat()
        }

        // Top pill badge — VIP feel
        card.addView(TextView(ctx).apply {
            text = "⚜  VIP  ACCESS  REQUIRED  ⚜"
            setTextColor(C_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.32f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(ctx, 16), dp(ctx, 7), dp(ctx, 16), dp(ctx, 7))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 40).toFloat()
                colors = intArrayOf(0x33F59E0B.toInt(), 0x1AF59E0B.toInt())
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                setStroke(dp(ctx, 1), 0x88FCD34D.toInt())
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // Glowing diamond icon with soft radial background
        val iconWrap = FrameLayout(ctx).apply {
            setPadding(0, dp(ctx, 16), 0, dp(ctx, 6))
        }
        iconWrap.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(0x55FCD34D.toInt(), 0x00FCD34D)
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(ctx, 60).toFloat()
            }
        }, FrameLayout.LayoutParams(dp(ctx, 120), dp(ctx, 120), Gravity.CENTER))
        val icon = TextView(ctx).apply {
            text = "💎"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            gravity = Gravity.CENTER
        }
        iconWrap.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        card.addView(iconWrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        icon.animate()
            .scaleX(1.08f).scaleY(1.08f)
            .setDuration(1400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction(object : Runnable {
                override fun run() {
                    icon.animate().scaleX(1f).scaleY(1f).setDuration(1400)
                        .withEndAction {
                            icon.animate().scaleX(1.08f).scaleY(1.08f)
                                .setDuration(1400).withEndAction(this).start()
                        }.start()
                }
            }).start()

        // Game title — big elegant caps
        card.addView(TextView(ctx).apply {
            text = gameLabel(game).uppercase()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            letterSpacing = 0.14f
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 2))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setShadowLayer(dp(ctx, 6).toFloat(), 0f, 0f, 0x55FCD34D.toInt())
        })

        // Sub-label under title
        card.addView(TextView(ctx).apply {
            text = "PREMIUM  UNLOCK"
            setTextColor(C_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            letterSpacing = 0.5f
            setPadding(0, 0, 0, dp(ctx, 12))
        })

        // Gold divider with side dots
        val dividerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        dividerRow.addView(dividerLine(ctx, dp(ctx, 60)))
        dividerRow.addView(TextView(ctx).apply {
            text = "◆"; setTextColor(C_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
        })
        dividerRow.addView(dividerLine(ctx, dp(ctx, 60)))
        card.addView(dividerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, 14) })

        // Server-driven message inside a subtle glass panel
        val messageView = TextView(ctx).apply {
            text = DepositGate.currentMessage(game)
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(ctx, 4).toFloat(), 1.2f)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 18), dp(ctx, 18), dp(ctx, 18), dp(ctx, 18))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 18).toFloat()
                colors = intArrayOf(0x1AFFFFFF, 0x0AFFFFFF)
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(ctx, 1), 0x22FCD34D.toInt())
            }
        }
        card.addView(messageView)
        root.tag = { msg: String -> messageView.text = msg }

        // ═════ PRIMARY — DEPOSIT NOW (rich green with gold accent stripe) ═════
        card.addView(buildPrimaryButton(ctx, "💰", "DEPOSIT NOW") {
            openInBrowser(ctx, DEPOSIT_URL)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 22) })

        // ═════ SMART REFRESH — opens RechargeHistory to trigger DKWin's own
        // API, DepositGate captures records via network layer, then auto
        // re-checks the gate. This is the user's requested flow. ═════
        card.addView(buildAccentButton(
            ctx, "🔄", "REFRESH DEPOSIT STATUS", C_BLUE,
        ) {
            Toast.makeText(ctx, "Refreshing… deposit records fetch করা হচ্ছে", Toast.LENGTH_SHORT).show()
            openInBrowser(ctx, RECHARGE_URL)
            // Give DKWin's SPA time to load + fire the API, then recheck.
            val h = Handler(Looper.getMainLooper())
            h.postDelayed({ DepositGate.refresh(ctx) }, 4500)
            h.postDelayed({ DepositGate.refresh(ctx) }, 9000)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 12) })

        // ═════ TERTIARY — open history without auto-recheck ═════
        card.addView(buildGhostButton(
            ctx, "📜  VIEW RECHARGE HISTORY", C_SLATE,
        ) {
            openInBrowser(ctx, RECHARGE_URL)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 10) })

        // Footer trust badges
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("🔒 SECURE", "⚡ INSTANT", "✓ AUTO UNLOCK").forEachIndexed { i, t ->
            if (i > 0) footer.addView(TextView(ctx).apply {
                text = "•"; setTextColor(C_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
            })
            footer.addView(TextView(ctx).apply {
                text = t
                setTextColor(C_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                letterSpacing = 0.18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        card.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 20) })

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

    private fun dividerLine(ctx: Context, width: Int): View = View(ctx).apply {
        background = GradientDrawable().apply {
            colors = intArrayOf(0x00FCD34D, 0xAAFCD34D.toInt())
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
        }
        layoutParams = LinearLayout.LayoutParams(width, dp(ctx, 1))
    }

    /** Big premium green CTA — the DEPOSIT button. */
    private fun buildPrimaryButton(
        ctx: Context, emoji: String, label: String, onClick: () -> Unit,
    ): View {
        val wrap = FrameLayout(ctx).apply {
            background = LayerDrawable(arrayOf(
                // Outer glow / ring
                GradientDrawable().apply {
                    cornerRadius = dp(ctx, 18).toFloat()
                    colors = intArrayOf(0x66FCD34D.toInt(), 0x33FCD34D.toInt())
                    orientation = GradientDrawable.Orientation.TL_BR
                },
                // Main green body
                GradientDrawable().apply {
                    cornerRadius = dp(ctx, 17).toFloat()
                    colors = intArrayOf(C_GREEN_1, C_GREEN_2, C_GREEN_3)
                    orientation = GradientDrawable.Orientation.TL_BR
                },
                // Top highlight sheen
                GradientDrawable().apply {
                    cornerRadius = dp(ctx, 17).toFloat()
                    colors = intArrayOf(0x44FFFFFF, 0x00FFFFFF)
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                },
            )).apply {
                setLayerInset(1, dp(ctx, 1), dp(ctx, 1), dp(ctx, 1), dp(ctx, 1))
                setLayerInset(2, dp(ctx, 1), dp(ctx, 1), dp(ctx, 1), dp(ctx, 1))
            }
            foreground = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), null, null)
            elevation = dp(ctx, 10).toFloat()
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 22), dp(ctx, 18), dp(ctx, 22), dp(ctx, 18))
        }
        row.addView(TextView(ctx).apply {
            text = emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(0, 0, dp(ctx, 12), 0)
        })
        row.addView(TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            letterSpacing = 0.24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setShadowLayer(dp(ctx, 3).toFloat(), 0f, dp(ctx, 1).toFloat(), 0x66000000)
        })
        row.addView(TextView(ctx).apply {
            text = " →"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(ctx, 8), 0, 0, 0)
        })
        wrap.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        return wrap
    }

    /** Coloured accent button (used for REFRESH). */
    private fun buildAccentButton(
        ctx: Context, emoji: String, label: String, tint: Int, onClick: () -> Unit,
    ): View {
        val wrap = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                colors = intArrayOf(
                    (tint and 0x00FFFFFF) or 0x33000000,
                    (tint and 0x00FFFFFF) or 0x14000000,
                )
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(ctx, 1), (tint and 0x00FFFFFF) or 0x77000000)
            }
            foreground = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
            elevation = dp(ctx, 4).toFloat()
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14))
        }
        row.addView(TextView(ctx).apply {
            text = emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, dp(ctx, 10), 0)
        })
        row.addView(TextView(ctx).apply {
            text = label
            setTextColor(tint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.2f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        wrap.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        return wrap
    }

    /** Subtle ghost button. */
    private fun buildGhostButton(
        ctx: Context, label: String, tint: Int, onClick: () -> Unit,
    ): TextView {
        return TextView(ctx).apply {
            text = label
            setTextColor(tint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(ctx, 18), dp(ctx, 12), dp(ctx, 18), dp(ctx, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(0x0AFFFFFF)
                setStroke(dp(ctx, 1), 0x22FFFFFF)
            }
            foreground = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null)
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
