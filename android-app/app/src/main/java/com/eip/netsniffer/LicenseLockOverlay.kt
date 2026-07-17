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
 * Full-screen lock overlay shown on Aviator / WinGo tabs when LicenseGate
 * reports LOCKED / UNKNOWN. Onboarding + in-app Register flow.
 *
 * REGISTER NOW button:
 *   1. Clears WebView cookies / cache / HTML5 storage (wipes old login)
 *   2. Switches to the Browser (DK Win) tab
 *   3. Loads the register page on the app's default domain (dkwin8.com)
 *      with our team invitation code pre-filled.
 */
object LicenseLockOverlay {

    // Uses the SAME default domain the browser tab already loads (dkwin8.com),
    // so branding / DNS / TLS stay consistent with the primary experience.
    private const val REGISTER_URL =
        "https://dkwin8.com/#/register?invitationCode=82824101415"

    private const val INTRO =
        "আপনার UID আমাদের টিমে খুঁজে পাওয়া যায়নি। আমাদের আন্ডারে কাজ করতে হলে নতুন একটি অ্যাকাউন্ট তৈরি করতে হবে। নিচের REGISTER NOW বাটনে চাপ দিলে পুরনো login/cookies সম্পূর্ণভাবে মুছে ফেলা হবে এবং আমাদের invite link দিয়ে নতুন অ্যাকাউন্ট তৈরির page খুলবে।"

    private val STEPS = listOf(
        "নিচের 🚀 REGISTER NOW বাটনে চাপ দিন — পুরনো cookies/session automatic clear হবে এবং register page খুলবে।",
        "সাইন-আপ form পূরণ করে নতুন অ্যাকাউন্ট তৈরি করুন (invite code আগে থেকেই বসানো থাকবে)।",
        "অ্যাকাউন্ট তৈরি হওয়ার পর সাইন-আপ page থেকে সরাসরি Login করুন। একবার Login হলে আমাদের system automatic আপনার UID detect করে unlock করে দেবে।",
        "যদি আগে থেকেই এই ব্রাউজারে অন্য account login করা থাকে — উপরের ডানদিকে profile → Log Out করে আবার আমাদের invite দিয়ে বানানো account দিয়ে Login করুন।",
        "যদি তাও detect না হয় — ফোনের Flight Mode একবার on/off করুন, প্রয়োজনে ফোন Restart করে আবার Login করুন। তারপর 🔄 RECHECK বাটনে চাপ দিন।",
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
                    val act = (ctx as? android.content.ContextWrapper)?.let { cw ->
                        var c: Context? = cw
                        while (c is android.content.ContextWrapper && c !is MainActivity) c = c.baseContext
                        c as? MainActivity
                    }
                    if (act != null) {
                        act.openRegisterInBrowser(REGISTER_URL)
                        android.widget.Toast.makeText(
                            ctx,
                            "পুরনো login/cookies clear করে register page খুলছে…",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
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
