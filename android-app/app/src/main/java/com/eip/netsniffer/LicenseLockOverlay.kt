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

    private const val TOP_ALERT =
        "⚠️ গুরুত্বপূর্ণ সতর্কতা\n\nMultiple account থেকে বিরত থাকার জন্য register করার আগে অবশ্যই আপনার IP পরিবর্তন করতে হবে।\n\n👉 Mobile Data চালু থাকলে: Data বন্ধ করুন → Flight Mode একবার On / Off করুন → আবার Data চালু করুন।\n\n👉 Wi-Fi চালু থাকলে: Wi-Fi একবার Off করে আবার On করুন।\n\nএরপর নিচের REGISTER NOW বাটনে চাপ দিন। নতুন IP-তে register করলে multiple account হিসেবে detect হবে না, account safe থাকবে।"

    private const val MAIN_MSG =
        "আপনি এখনো আমাদের EIP TEAM-এর under-এ account করেননি। নিচের ধাপগুলো ঠিকভাবে follow করুন —\n\n① নিচের REGISTER NOW বাটনে click করুন। এতে পুরনো cookies clear হয়ে DK Win-এর register page খুলবে (invite code আগে থেকেই বসানো থাকবে)।\n\n② নতুন account তৈরি করুন। Register শেষ হলে DK Win আপনাকে সরাসরি auto-login করিয়ে দেবে — কিন্তু এই auto-login-এ login API run হয় না, তাই আমরা আপনার parent invite code capture করতে পারি না।\n\n③ এই কারণে অবশ্যই DK Win-এর ভেতরে গিয়ে একবার Logout করুন, তারপর সেই একই UID + password দিয়ে আবার নিজে হাতে Login করুন। এবার login API run হবে এবং আমরা আপনার team verify করতে পারব।\n\n④ Login সফল হলে এই screen-এ ফিরে এসে নিচের RECHECK বাটনে চাপ দিন — সব ঠিক থাকলে system unlock হয়ে যাবে।"

    private const val AFTER_REGISTER_STEPS =
        "📋 REGISTER-এর পরে অবশ্যই করণীয়:\n\n1️⃣  DK Win tab-এ যান → Profile / Account menu খুলুন → Logout চাপুন।\n\n2️⃣  আবার Login button-এ চাপ দিয়ে নিজে হাতে UID ও password লিখে Login করুন (auto-login নয়)।\n\n3️⃣  Login সফল হওয়ার পর এই screen-এ ফিরে এসে RECHECK চাপুন।\n\n❗ Logout না করে RECHECK দিলে verify হবে না, কারণ register-এর auto-login-এ login API call হয় না।"



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

        // Three overlays: login-first, register/onboarding, and tamper shame.
        val loginOverlay = buildLoginOverlay(ctx).apply { visibility = View.GONE }
        val registerOverlay = buildOverlay(ctx).apply { visibility = View.GONE }
        val shameOverlay = buildShameOverlay(ctx).apply { visibility = View.GONE }
        fl.addView(loginOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        fl.addView(registerOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        fl.addView(shameOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        fun apply(st: LicenseGate.State?) {
            val locked = st == null || st.locked
            val tampered = st?.tampered == true
            // No login attempt captured yet → ask user to login first, don't push register.
            val needsLogin = locked && !tampered && (st?.uid.isNullOrBlank())
            shameOverlay.visibility = if (locked && tampered) View.VISIBLE else View.GONE
            loginOverlay.visibility = if (needsLogin) View.VISIBLE else View.GONE
            registerOverlay.visibility = if (locked && !tampered && !needsLogin) View.VISIBLE else View.GONE
        }
        LicenseGate.state.observe(fragment.viewLifecycleOwner) { apply(it) }
        apply(LicenseGate.state.value)
        return fl
    }

    /** Shown before the user has logged in on DK Win at all. */
    private fun buildLoginOverlay(ctx: Context): View {
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
                setStroke(dp(ctx, 1), 0xFF22D3EE.toInt())
            }
        }
        card.addView(TextView(ctx).apply {
            text = "🔐"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            gravity = Gravity.CENTER
        })
        card.addView(TextView(ctx).apply {
            text = "প্রথমে লগইন করুন"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            letterSpacing = 0.1f
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 4))
        })
        card.addView(TextView(ctx).apply {
            text = "LOGIN REQUIRED"
            setTextColor(0xFF22D3EE.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            setPadding(0, 0, 0, dp(ctx, 18))
        })
        card.addView(TextView(ctx).apply {
            text = "⚠️ RED ALERT\n\nSignal / Auto Bet ব্যবহার করতে হলে আপনার DK Win account অবশ্যই EIP TEAM-এর under invite দিয়ে তৈরি থাকতে হবে। অন্য team, self-created বা ভুল invite-এর account হলে system unlock হবে না।\n\nপ্রথমে DK Win tab-এ Login করুন। Login সফল হলে system automatic UID check করবে — EIP TEAM-এর under থাকলে unlock হবে, না থাকলে register instruction দেখাবে।"
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(ctx, 4).toFloat(), 1f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                colors = intArrayOf(0x553B0710.toInt(), 0x33111827.toInt())
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                setStroke(dp(ctx, 1), 0xFFEF4444.toInt())
            }
        })
        card.addView(Button(ctx).apply {
            text = "🌐  OPEN DK WIN LOGIN"
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
                    act?.switchToBrowserTab()
                } catch (_: Throwable) {}
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 18) })
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
            setOnClickListener { LicenseGate.refresh(ctx) }
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

    /** Full-screen official-build verification screen for tampered / repackaged builds. */
    private fun buildShameOverlay(ctx: Context): View {
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(0xF20A0F1A.toInt())
            isClickable = true
            isFocusable = true
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
                colors = intArrayOf(0xFF0B1120.toInt(), 0xFF111827.toInt())
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(dp(ctx, 1), 0xFFF59E0B.toInt())
            }
        }

        card.addView(TextView(ctx).apply {
            text = "🛡️"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 64f)
            gravity = Gravity.CENTER
        })
        card.addView(TextView(ctx).apply {
            text = "Official App Verification"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 4))
        })
        card.addView(TextView(ctx).apply {
            text = "SECURE BUILD CHECK"
            setTextColor(0xFFFDE68A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            setPadding(0, 0, 0, dp(ctx, 18))
        })

        card.addView(TextView(ctx).apply {
            text = "এই app-এর secure verification সম্পন্ন করা যায়নি। অনুগ্রহ করে official EIP TEAM APK install করুন এবং DK Win tab-এ Login করে আবার RECHECK দিন।\n\nLogin করার আগে reset/recheck দিলে কোনো harsh warning দেখাবে না — শুধু login এবং team verification instruction দেখাবে।"
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(ctx, 4).toFloat(), 1f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(0x22F59E0B.toInt())
                setStroke(dp(ctx, 1), 0x66F59E0B.toInt())
            }
        })

        // Detected reasons
        val reasons = TamperGuard.reasons.ifEmpty { listOf("server_reported") }
        card.addView(TextView(ctx).apply {
            text = "Verification details:"
            setTextColor(0xFFF59E0B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(ctx, 18), 0, dp(ctx, 6))
        })
        reasons.forEach { r ->
            card.addView(TextView(ctx).apply {
                text = "  • $r"
                setTextColor(0xFFFDE68A.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
            })
        }

        val lines = listOf(
            "সমাধান:",
            "• Official APK install করুন।",
            "• DK Win tab-এ EIP TEAM-এর under account দিয়ে Login করুন।",
            "• এরপর RECHECK দিন — সব ঠিক থাকলে system unlock হবে।",
        )
        card.addView(TextView(ctx).apply {
            text = lines.joinToString("\n")
            setTextColor(0xFFE2E8F0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            setPadding(0, dp(ctx, 18), 0, 0)
        })

        card.addView(TextView(ctx).apply {
            text = "— EIP TEAM Security"
            setTextColor(0xFF64748B.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 22), 0, 0)
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

        // Top red alert card
        card.addView(TextView(ctx).apply {
            text = TOP_ALERT
            setTextColor(0xFFFECACA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                colors = intArrayOf(0x55450A0A.toInt(), 0x33111827.toInt())
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                setStroke(dp(ctx, 1), 0xFFEF4444.toInt())
            }
        })

        // Main message card
        card.addView(TextView(ctx).apply {
            text = MAIN_MSG
            setTextColor(0xFFFDE68A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14).toFloat()
                setColor(0x22F59E0B.toInt())
                setStroke(dp(ctx, 1), 0x66F59E0B.toInt())
            }
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(ctx, 12)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 12) })


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
