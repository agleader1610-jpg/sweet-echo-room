package com.eip.netsniffer

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
 * Full-screen lock overlay shown on Aviator / WinGo tabs when LicenseGate
 * reports LOCKED / UNKNOWN. Displays team-onboarding instructions and a
 * prominent "Register Now" button that opens the team invite link.
 */
object LicenseLockOverlay {

    private const val REGISTER_URL =
        "https://dkwin7.com/#/register?invitationCode=82824101415"

    private const val INTRO =
        "আপনার UID আমাদের টিমে খুঁজে পাওয়া যায়নি, তাই দয়া করে নিচের কথাগুলো ভালোভাবে পড়ুন।"

    private val STEPS = listOf(
        "আপনি এখনো আমাদের টিমে যুক্ত হননি। আমাদের আন্ডারে কাজ করতে হলে একটি নতুন অ্যাকাউন্ট তৈরি করতে হবে।",
        "আপনি আগে পুরনো অ্যাকাউন্ট ব্যবহার করেছেন, তাই ইন্টারনেট সংযোগের তথ্য সংরক্ষিত থাকতে পারে। এজন্য সংযোগ নতুনভাবে refresh করা জরুরি।",
        "ব্রাউজারের history ও cache মুছে ফেলুন, অথবা অন্য একটি ব্রাউজার ব্যবহার করুন।",
        "ফোনের Flight Mode একবার চালু করে আবার বন্ধ করুন। অথবা মোবাইল ডাটা বন্ধ করে আবার চালু করুন। প্রয়োজনে সিম একবার বন্ধ করে আবার চালু করুন।",
        "সবশেষে ফোনটি একবার Restart করুন (বন্ধ করে আবার চালু করুন)।",
        "ফোন চালু হওয়ার পর নিচের REGISTER NOW বাটনে চাপ দিয়ে আমাদের link থেকে নতুন অ্যাকাউন্ট তৈরি করুন। অ্যাকাউন্ট তৈরি হয়ে গেলে আমাদের সাথে যোগাযোগ করবেন। ধন্যবাদ।",
    )

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

        val overlay = buildOverlay(ctx)
        overlay.visibility = View.GONE
        fl.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        LicenseGate.state.observe(fragment.viewLifecycleOwner) { st ->
            val locked = st == null || st.locked
            overlay.visibility = if (locked) View.VISIBLE else View.GONE
        }
        if (LicenseGate.isLocked()) overlay.visibility = View.VISIBLE
        return fl
    }

    private fun buildOverlay(ctx: Context): View {
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(0xF20A0F1A.toInt())
            isClickable = true
            isFocusable = true
        }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            setPadding(dp(ctx, 18), dp(ctx, 18), dp(ctx, 18), dp(ctx, 18))
        }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 22), dp(ctx, 20), dp(ctx, 22))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 22).toFloat()
                colors = intArrayOf(0xFF0B1120.toInt(), 0xFF11162B.toInt())
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(dp(ctx, 1), 0xFF6D28D9.toInt())
            }
        }

        // Header badge
        card.addView(TextView(ctx).apply {
            text = "🔒  ACCESS LOCKED"
            setTextColor(0xFFEF4444.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 999).toFloat()
                setColor(0x33EF4444.toInt())
                setStroke(dp(ctx, 1), 0x66EF4444.toInt())
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER })

        // Title
        card.addView(TextView(ctx).apply {
            text = "EIP TEAM"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            letterSpacing = 0.25f
            setPadding(0, dp(ctx, 14), 0, dp(ctx, 2))
        })
        card.addView(TextView(ctx).apply {
            text = "Signal Access · Team Verification Required"
            setTextColor(0xFF94A3B8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(ctx, 18))
        })

        // Intro (highlight card)
        card.addView(TextView(ctx).apply {
            text = INTRO
            setTextColor(0xFFFDE68A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(0x22F59E0B.toInt())
                setStroke(dp(ctx, 1), 0x66F59E0B.toInt())
            }
        })

        // Steps
        val stepsBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 14), 0, dp(ctx, 4))
        }
        STEPS.forEachIndexed { idx, text ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
            }
            row.addView(TextView(ctx).apply {
                this.text = (idx + 1).toString()
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF6D28D9.toInt())
                }
                val sz = dp(ctx, 24)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    topMargin = dp(ctx, 2); rightMargin = dp(ctx, 10)
                }
            })
            row.addView(TextView(ctx).apply {
                this.text = text
                setTextColor(0xFFE2E8F0.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            })
            stepsBox.addView(row)
        }
        card.addView(stepsBox)

        // Register button
        card.addView(Button(ctx).apply {
            text = "🚀  REGISTER NOW"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            letterSpacing = 0.15f
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                colors = intArrayOf(0xFF22D3EE.toInt(), 0xFF6D28D9.toInt())
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14))
            setOnClickListener {
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(REGISTER_URL))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Throwable) {}
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 18) })

        // Link hint
        card.addView(TextView(ctx).apply {
            text = REGISTER_URL
            setTextColor(0xFF60A5FA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
        })

        // Recheck button
        card.addView(Button(ctx).apply {
            text = "🔄  RECHECK LOGIN"
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 12).toFloat()
                setColor(0x22FFFFFF.toInt())
                setStroke(dp(ctx, 1), 0x33FFFFFF.toInt())
            }
            setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
            setOnClickListener { LicenseGate.refresh(ctx) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 10) })

        // Footer
        card.addView(TextView(ctx).apply {
            text = "অ্যাকাউন্ট তৈরি হয়ে গেলে আমাদের সাথে যোগাযোগ করুন · ধন্যবাদ"
            setTextColor(0xFF64748B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 16), 0, 0)
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

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
