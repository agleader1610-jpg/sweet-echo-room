package com.eip.netsniffer

import android.animation.ValueAnimator
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Premium deposit-required overlay — redesigned edition.
 *
 * Layout (top → bottom):
 *   ┌───────────────────────────────────────┐
 *   │  🔐  ACCESS REQUIRED  (small pill)   │
 *   │  <Game name>                          │
 *   │  ─────── gold divider ───────         │
 *   │  <server message>                     │
 *   │  ┌──── refresh status card ────┐     │
 *   │  │  ⚪ Idle / 🔄 Detecting…    │     │
 *   │  │  status text + progress bar │     │
 *   │  └────────────────────────────┘     │
 *   │  [ 🔄 REFRESH DEPOSIT STATUS ]  ← full width
 *   │  [ 💰 DEPOSIT NOW ]  [ 📜 HISTORY ]  ← same row
 *   │  🔒 SECURE • ⚡ INSTANT • ✓ AUTO     │
 *   └───────────────────────────────────────┘
 *
 * When a locked game becomes allowed, a slide-in success card briefly
 * confirms unlock and mentions the satisfied rule before dismissal.
 */
object DepositLockOverlay {

    private const val RECHARGE_URL = "https://dkwin8.com/#/wallet/RechargeHistory"
    private const val DEPOSIT_URL  = "https://dkwin8.com/#/wallet/Recharge"

    // Palette — luxurious champagne-gold on midnight navy.
    private const val C_GOLD      = 0xFFFCD34D.toInt()
    private const val C_GOLD_SOFT = 0xFFF59E0B.toInt()
    private const val C_GREEN_1   = 0xFF34D399.toInt()
    private const val C_GREEN_2   = 0xFF10B981.toInt()
    private const val C_GREEN_3   = 0xFF047857.toInt()
    private const val C_BLUE      = 0xFF60A5FA.toInt()
    private const val C_SLATE     = 0xFFCBD5E1.toInt()
    private const val C_MUTED     = 0xFF64748B.toInt()
    private const val C_AMBER     = 0xFFF59E0B.toInt()

    private fun gameLabel(game: String): String = when (game) {
        DepositGate.GAME_WINGO       -> "WinGo Signal"
        DepositGate.GAME_WINGO_AUTO  -> "WinGo AutoBet"
        else                         -> "Aviator Signal"
    }

    /** Refresh action UI state. */
    private enum class RefreshUi { IDLE, DETECTING, FOUND, NOT_FOUND }

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

        val handles = Handles()
        val overlay = buildOverlay(ctx, fragment, game, handles).apply { visibility = View.GONE }
        val successCard = buildSuccessCard(ctx, game).apply { visibility = View.GONE }

        fl.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        fl.addView(successCard, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply {
            leftMargin = dp(ctx, 20); rightMargin = dp(ctx, 20)
        })

        var wasLocked = DepositGate.isLocked(game)

        fun apply() {
            val licenseLocked = LicenseGate.isLocked()
            val depositLocked = DepositGate.isLocked(game)
            val show = !licenseLocked && depositLocked
            overlay.visibility = if (show) View.VISIBLE else View.GONE

            val st = DepositGate.stateFor(game).value
            if (show && st != null) {
                handles.message.text = st.message.ifBlank { "🔒 Deposit প্রয়োজন — নিচের বাটন ব্যবহার করুন।" }
            }

            // Refresh state → FOUND / NOT_FOUND when a refresh was pending.
            if (handles.refreshState == RefreshUi.DETECTING) {
                if (!depositLocked) setRefresh(ctx, handles, RefreshUi.FOUND)
                // NOT_FOUND transition handled by the delayed watchdog below.
            }

            // Unlock transition → briefly show elegant success card.
            if (wasLocked && !depositLocked && !licenseLocked) {
                showSuccess(ctx, successCard, st)
            }
            wasLocked = depositLocked
        }
        LicenseGate.state.observe(fragment.viewLifecycleOwner) { apply() }
        DepositGate.stateFor(game).observe(fragment.viewLifecycleOwner) { apply() }
        apply()
        return fl
    }

    // ═════════════════════════ Overlay body ═════════════════════════

    private class Handles {
        lateinit var message: TextView
        lateinit var statusIcon: TextView
        lateinit var statusText: TextView
        lateinit var statusChip: TextView
        lateinit var progress: ProgressBar
        var refreshState: RefreshUi = RefreshUi.IDLE
    }

    private fun buildOverlay(
        ctx: Context, fragment: Fragment, game: String, h: Handles,
    ): View {
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(0xF7050814.toInt())
            isClickable = true; isFocusable = true
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setPadding(dp(ctx, 16), dp(ctx, 20), dp(ctx, 16), dp(ctx, 20))
        }

        // Card with thin gold gradient ring.
        val cardBg = LayerDrawable(arrayOf(
            GradientDrawable().apply {
                cornerRadius = dp(ctx, 24).toFloat()
                colors = intArrayOf(0x55FCD34D.toInt(), 0x22F59E0B.toInt(), 0x55FCD34D.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
            },
            GradientDrawable().apply {
                cornerRadius = dp(ctx, 22).toFloat()
                colors = intArrayOf(0xFF0A1024.toInt(), 0xFF0F1730.toInt(), 0xFF0A0F22.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
            },
        )).apply { setLayerInset(1, dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2)) }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18), dp(ctx, 20), dp(ctx, 18), dp(ctx, 18))
            background = cardBg
            elevation = dp(ctx, 14).toFloat()
        }

        // ── Small top pill (no big diamond icon) ──
        card.addView(TextView(ctx).apply {
            text = "🔐  ACCESS REQUIRED"
            setTextColor(C_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            letterSpacing = 0.28f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 40).toFloat()
                colors = intArrayOf(0x2AF59E0B, 0x14F59E0B)
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                setStroke(dp(ctx, 1), 0x88FCD34D.toInt())
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        // ── Game title ──
        card.addView(TextView(ctx).apply {
            text = gameLabel(game).uppercase()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            letterSpacing = 0.14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setShadowLayer(dp(ctx, 5).toFloat(), 0f, 0f, 0x44FCD34D.toInt())
            setPadding(0, dp(ctx, 12), 0, dp(ctx, 2))
        })
        card.addView(TextView(ctx).apply {
            text = "PREMIUM UNLOCK"
            setTextColor(C_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            gravity = Gravity.CENTER
            letterSpacing = 0.45f
            setPadding(0, 0, 0, dp(ctx, 12))
        })

        // ── Gold divider ──
        val dividerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        dividerRow.addView(dividerLine(ctx, dp(ctx, 56)))
        dividerRow.addView(TextView(ctx).apply {
            text = "◆"; setTextColor(C_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
        })
        dividerRow.addView(dividerLine(ctx, dp(ctx, 56)))
        card.addView(dividerRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(ctx, 12) })

        // ── Server message panel ──
        h.message = TextView(ctx).apply {
            text = DepositGate.currentMessage(game)
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setLineSpacing(dp(ctx, 4).toFloat(), 1.2f)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                colors = intArrayOf(0x1AFFFFFF, 0x0AFFFFFF)
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(ctx, 1), 0x22FCD34D.toInt())
            }
        }
        card.addView(h.message)

        // ── Live refresh status card ──
        val statusBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(0x14000000)
                setStroke(dp(ctx, 1), 0x2260A5FA.toInt())
            }
        }
        val statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        h.statusIcon = TextView(ctx).apply {
            text = "⚪"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, dp(ctx, 10), 0)
        }
        h.statusText = TextView(ctx).apply {
            text = "Refresh Status: Idle"
            setTextColor(C_SLATE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            letterSpacing = 0.05f
        }
        h.statusChip = TextView(ctx).apply {
            text = "IDLE"
            setTextColor(C_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            letterSpacing = 0.2f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 20).toFloat()
                setColor(0x22FFFFFF)
                setStroke(dp(ctx, 1), 0x33FFFFFF)
            }
        }
        statusRow.addView(h.statusIcon)
        statusRow.addView(h.statusText, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusRow.addView(h.statusChip)
        statusBox.addView(statusRow)

        h.progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(C_BLUE)
        }
        statusBox.addView(h.progress, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 6),
        ).apply { topMargin = dp(ctx, 10) })
        card.addView(statusBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 12) })

        // ── Deposit + History row (same line, responsive, balanced) ──
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
        }
        actionRow.addView(buildPrimaryButton(ctx, "💰", "DEPOSIT", isGreen = true) {
            openInBrowser(ctx, DEPOSIT_URL)
        }, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(ctx, 8) })
        actionRow.addView(buildOutlineButton(ctx, "📜", "HISTORY") {
            openInBrowser(ctx, RECHARGE_URL)
        }, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(ctx, 8) })
        card.addView(actionRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 14) })

        // ── Explicit hint linking Deposit → Refresh ──
        card.addView(TextView(ctx).apply {
            text = "✅ Deposit শেষ করে নিচের 🔄 REFRESH STATUS বাটনে চাপ দিন — কয়েক সেকেন্ডেই automatic unlock হয়ে যাবে।"
            setTextColor(0xFFFDE68A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setLineSpacing(dp(ctx, 3).toFloat(), 1.15f)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(0x1AFCD34D.toInt())
                setStroke(dp(ctx, 1), 0x44FCD34D.toInt())
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 12) })

        // ── Refresh button (full width, bottom) ──
        card.addView(buildPrimaryButton(ctx, "🔄", "REFRESH STATUS", isGreen = false) {
            triggerRefresh(ctx, h)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 10) })


        // ── Footer trust ──
        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        }
        listOf("🔒 SECURE", "⚡ INSTANT", "✓ AUTO UNLOCK").forEachIndexed { i, t ->
            if (i > 0) footer.addView(TextView(ctx).apply {
                text = "•"; setTextColor(C_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setPadding(dp(ctx, 7), 0, dp(ctx, 7), 0)
            })
            footer.addView(TextView(ctx).apply {
                text = t; setTextColor(C_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
                letterSpacing = 0.16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        card.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 16) })

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

    // ═════════════════════════ Refresh logic ═════════════════════════

    private fun triggerRefresh(ctx: Context, h: Handles) {
        setRefresh(ctx, h, RefreshUi.DETECTING)
        // Push browser to Recharge History so the site's own API fires and
        // DepositGate captures records from the network layer.
        openInBrowser(ctx, RECHARGE_URL)

        val loop = Handler(Looper.getMainLooper())
        loop.postDelayed({ DepositGate.refresh(ctx) }, 4500)
        loop.postDelayed({ DepositGate.refresh(ctx) }, 9000)
        loop.postDelayed({
            // Final check — if still detecting after 12s, mark not_found.
            if (h.refreshState == RefreshUi.DETECTING) {
                setRefresh(ctx, h, RefreshUi.NOT_FOUND)
            }
        }, 12000)
    }

    private fun setRefresh(ctx: Context, h: Handles, state: RefreshUi) {
        h.refreshState = state
        when (state) {
            RefreshUi.IDLE -> {
                h.statusIcon.text = "⚪"
                h.statusText.text = "Refresh Status: Idle"
                h.statusText.setTextColor(C_SLATE)
                setChip(ctx, h.statusChip, "IDLE", C_MUTED)
                h.progress.visibility = View.GONE
            }
            RefreshUi.DETECTING -> {
                h.statusIcon.text = "🔄"
                h.statusText.text = "Detecting your deposits…"
                h.statusText.setTextColor(C_BLUE)
                setChip(ctx, h.statusChip, "DETECTING", C_BLUE)
                h.progress.visibility = View.VISIBLE
                animateProgress(h.progress, 0, 90, 11000)
            }
            RefreshUi.FOUND -> {
                h.statusIcon.text = "✅"
                h.statusText.text = "Deposit detect হয়েছে — unlock হচ্ছে…"
                h.statusText.setTextColor(C_GREEN_1)
                setChip(ctx, h.statusChip, "FOUND", C_GREEN_1)
                h.progress.progress = 100
                h.progress.visibility = View.VISIBLE
            }
            RefreshUi.NOT_FOUND -> {
                h.statusIcon.text = "⚠️"
                h.statusText.text = "কোনো নতুন deposit পাওয়া যায়নি — আবার চেষ্টা করুন।"
                h.statusText.setTextColor(C_AMBER)
                setChip(ctx, h.statusChip, "NOT FOUND", C_AMBER)
                h.progress.progress = 100
                h.progress.visibility = View.VISIBLE
            }
        }
    }

    private fun animateProgress(bar: ProgressBar, from: Int, to: Int, duration: Long) {
        ValueAnimator.ofInt(from, to).apply {
            this.duration = duration
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { bar.progress = it.animatedValue as Int }
            start()
        }
    }

    private fun setChip(ctx: Context, chip: TextView, label: String, tint: Int) {
        chip.text = label
        chip.setTextColor(tint)
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(ctx, 20).toFloat()
            setColor((tint and 0x00FFFFFF) or 0x22000000)
            setStroke(dp(ctx, 1), (tint and 0x00FFFFFF) or 0x66000000)
        }
    }

    // ═════════════════════════ Success card ═════════════════════════

    private fun buildSuccessCard(ctx: Context, game: String): View {
        val wrap = FrameLayout(ctx).apply {
            background = LayerDrawable(arrayOf(
                GradientDrawable().apply {
                    cornerRadius = dp(ctx, 22).toFloat()
                    colors = intArrayOf(0x66FCD34D.toInt(), 0x2210B981.toInt())
                    orientation = GradientDrawable.Orientation.TL_BR
                },
                GradientDrawable().apply {
                    cornerRadius = dp(ctx, 20).toFloat()
                    colors = intArrayOf(0xFF06231A.toInt(), 0xFF082A22.toInt())
                    orientation = GradientDrawable.Orientation.TL_BR
                },
            )).apply { setLayerInset(1, dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2)) }
            elevation = dp(ctx, 20).toFloat()
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 22))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(TextView(ctx).apply {
            text = "✅"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
            gravity = Gravity.CENTER
        })
        col.addView(TextView(ctx).apply {
            text = "DEPOSIT VERIFIED"
            setTextColor(C_GREEN_1)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.35f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(ctx, 6), 0, 0)
        })
        col.addView(TextView(ctx).apply {
            text = "${gameLabel(game)} unlock হয়েছে"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 4))
            tag = "title"
        })
        col.addView(TextView(ctx).apply {
            text = ""
            setTextColor(0xFFBBF7D0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setLineSpacing(dp(ctx, 3).toFloat(), 1.1f)
            tag = "sub"
        })
        wrap.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ))
        return wrap
    }

    private fun showSuccess(ctx: Context, card: View, st: DepositGate.State?) {
        val col = (card as FrameLayout).getChildAt(0) as LinearLayout
        val sub = col.findViewWithTag<TextView>("sub")
        val summary = when (st?.reason) {
            "override_unlimited" -> "Admin-এর pass detect হয়েছে — চিরকাল unlocked।"
            else -> buildString {
                if ((st?.recentCount ?: 0) > 0 && (st?.recencyDays ?: 0) > 0) {
                    append("শেষ ${st!!.recencyDays} দিনে ${st.recentCount} টি deposit পাওয়া গেছে।")
                } else if ((st?.lifetimeCount ?: 0) > 0) {
                    append("মোট ${st!!.lifetimeCount} টি deposit verified হয়েছে।")
                } else {
                    append("সব শর্ত পূরণ হয়েছে — এখন feature টি চালু।")
                }
            }
        }
        sub.text = summary
        card.visibility = View.VISIBLE
        card.alpha = 0f
        card.translationY = dp(ctx, 12).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(260).start()
        Handler(Looper.getMainLooper()).postDelayed({
            card.animate().alpha(0f).setDuration(280).withEndAction {
                card.visibility = View.GONE
            }.start()
        }, 3200)
    }

    // ═════════════════════════ Buttons ═════════════════════════

    /** Full-width bold CTA — green when isGreen, blue outline gradient otherwise. */
    private fun buildPrimaryButton(
        ctx: Context, emoji: String, label: String, isGreen: Boolean, onClick: () -> Unit,
    ): View {
        val bodyColors = if (isGreen)
            intArrayOf(C_GREEN_1, C_GREEN_2, C_GREEN_3)
        else
            intArrayOf(0xFF1E40AF.toInt(), 0xFF1D4ED8.toInt(), 0xFF2563EB.toInt())
        val ringColors = if (isGreen)
            intArrayOf(0x66FCD34D.toInt(), 0x33FCD34D.toInt())
        else
            intArrayOf(0x8860A5FA.toInt(), 0x4460A5FA.toInt())

        val wrap = FrameLayout(ctx).apply {
            background = LayerDrawable(arrayOf(
                GradientDrawable().apply {
                    cornerRadius = dp(ctx, 16).toFloat()
                    colors = ringColors
                    orientation = GradientDrawable.Orientation.TL_BR
                },
                GradientDrawable().apply {
                    cornerRadius = dp(ctx, 15).toFloat()
                    colors = bodyColors
                    orientation = GradientDrawable.Orientation.TL_BR
                },
                GradientDrawable().apply {
                    cornerRadius = dp(ctx, 15).toFloat()
                    colors = intArrayOf(0x44FFFFFF, 0x00FFFFFF)
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                },
            )).apply {
                setLayerInset(1, dp(ctx, 1), dp(ctx, 1), dp(ctx, 1), dp(ctx, 1))
                setLayerInset(2, dp(ctx, 1), dp(ctx, 1), dp(ctx, 1), dp(ctx, 1))
            }
            foreground = RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), null, null)
            elevation = dp(ctx, if (isGreen) 8 else 6).toFloat()
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        }
        row.addView(TextView(ctx).apply {
            text = emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setPadding(0, 0, dp(ctx, 8), 0)
        })
        row.addView(TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            letterSpacing = 0.14f
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setShadowLayer(dp(ctx, 2).toFloat(), 0f, dp(ctx, 1).toFloat(), 0x66000000)
        })
        wrap.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        return wrap
    }

    /** Outline button — used for HISTORY on the action row. */
    private fun buildOutlineButton(
        ctx: Context, emoji: String, label: String, onClick: () -> Unit,
    ): View {
        val wrap = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                colors = intArrayOf(0x18FFFFFF, 0x08FFFFFF)
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(ctx, 1), 0x55FCD34D.toInt())
            }
            foreground = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
            elevation = dp(ctx, 3).toFloat()
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        }
        row.addView(TextView(ctx).apply {
            text = emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, dp(ctx, 8), 0)
        })
        row.addView(TextView(ctx).apply {
            text = label
            setTextColor(C_GOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.14f
            maxLines = 1
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        wrap.addView(row, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        return wrap
    }

    private fun dividerLine(ctx: Context, width: Int): View = View(ctx).apply {
        background = GradientDrawable().apply {
            colors = intArrayOf(0x00FCD34D, 0xAAFCD34D.toInt())
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
        }
        layoutParams = LinearLayout.LayoutParams(width, dp(ctx, 1))
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
