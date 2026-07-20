package com.eip.netsniffer

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.eip.netsniffer.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("net_sniffer", Context.MODE_PRIVATE) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        CaptureUploader.init(applicationContext)
        CaptureStore.init(applicationContext)
        TamperGuard.init(applicationContext)
        val devId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        LicenseGate.init(applicationContext, devId)
        DepositGate.init(applicationContext, devId)
        NotificationStore.start(applicationContext)
        // If we already know a uid, share it with the notif store immediately.
        val savedUid = getSharedPreferences("eip_deposit", Context.MODE_PRIVATE).getString("uid", null)
        if (!savedUid.isNullOrBlank()) NotificationStore.setUid(savedUid)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Deposit gate: once per uid per day, force browser to RechargeHistory
        // so GetRechargeRecord fires and we can re-verify the gate.
        DepositGate.needsRedirect.observe(this) { needs ->
            if (needs == true) {
                DepositGate.needsRedirect.postValue(false)
                b.pager.setCurrentItem(0, true)
                prefs.edit().putInt(PREF_LAST_TAB, 0).apply()
                b.pager.post {
                    getBrowserFragment()?.navigateTo(RECHARGE_HISTORY_URL)
                }
            }
        }

        b.pager.adapter = PagerAdapter(this)
        b.pager.offscreenPageLimit = 5
        b.pager.isUserInputEnabled = false
        b.pager.setCurrentItem(prefs.getInt(PREF_LAST_TAB, 0), false)

        setupDraggableFab()
        setupNotificationBell()

        // Remote force-update gate (fail-open: silent on any failure).
        VersionChecker.check(this)

        // Telegram channel invite on app open.
        b.root.post {
            showTelegramJoinDialog()
            // First-run guided coach marks (bell → VIP FAB).
            b.root.postDelayed({
                OnboardingCoach.maybeShow(this, b.bellWrap, b.launcherFab)
            }, 600)
        }
        // Review reminder এখন panel-এ ঢুকলে তারপর দেখাবে (Aviator / WinGo / AutoBet fragment onResume-এ)।
    }


    // ---------------- Telegram join dialog ----------------

    private fun showTelegramJoinDialog() {
        if (isFinishing || isDestroyed) return
        val view = layoutInflater.inflate(R.layout.dialog_telegram_join, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        view.findViewById<Button>(R.id.tgSkipBtn).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.tgJoinBtn).setOnClickListener {
            openTelegramChannel(TELEGRAM_CHANNEL_USERNAME, TELEGRAM_CHANNEL_URL)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun openTelegramChannel(username: String, httpsFallback: String) {
        // 1) Try native Telegram app via tg:// deep link
        try {
            val tgIntent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("tg://resolve?domain=$username")
            )
            tgIntent.setPackage("org.telegram.messenger")
            startActivity(tgIntent)
            return
        } catch (_: Throwable) { /* fall through */ }
        // 2) Try Telegram X
        try {
            val tgxIntent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("tg://resolve?domain=$username")
            )
            tgxIntent.setPackage("org.thunderdog.challegram")
            startActivity(tgxIntent)
            return
        } catch (_: Throwable) { /* fall through */ }
        // 3) Any app that handles tg://
        try {
            startActivity(android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("tg://resolve?domain=$username")
            ))
            return
        } catch (_: Throwable) { /* fall through */ }
        // 4) https fallback in external browser
        try {
            startActivity(android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(httpsFallback)
            ))
        } catch (_: Throwable) {
            android.widget.Toast.makeText(this, "Telegram open করতে ব্যর্থ", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- Draggable FAB ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableFab() {
        val fab = b.launcherFab

        fab.post {
            val x = prefs.getFloat(PREF_FAB_X, Float.NaN)
            val y = prefs.getFloat(PREF_FAB_Y, Float.NaN)
            if (!x.isNaN() && !y.isNaN()) {
                fab.x = clampX(fab, x); fab.y = clampY(fab, y)
            }
            // Gentle breathing pulse to hint interactivity
            fab.animate()
                .scaleX(1.08f).scaleY(1.08f)
                .setDuration(1400)
                .withEndAction {
                    fab.animate().scaleX(1f).scaleY(1f).setDuration(1400).start()
                }.start()
        }

        var downX = 0f; var downY = 0f
        var startRawX = 0f; var startRawY = 0f
        var dragging = false
        val slop = (8 * resources.displayMetrics.density)

        fab.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = v.x; downY = v.y
                    startRawX = ev.rawX; startRawY = ev.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startRawX
                    val dy = ev.rawY - startRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        v.x = clampX(v, downX + dx)
                        v.y = clampY(v, downY + dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        prefs.edit().putFloat(PREF_FAB_X, v.x).putFloat(PREF_FAB_Y, v.y).apply()
                    } else {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        fab.setOnClickListener { showLauncherSheet() }
    }

    private fun clampX(v: View, x: Float): Float {
        val parent = v.parent as View
        return max(0f, min(x, (parent.width - v.width).toFloat()))
    }
    private fun clampY(v: View, y: Float): Float {
        val parent = v.parent as View
        return max(0f, min(y, (parent.height - v.height).toFloat()))
    }

    // ---------------- Launcher bottom sheet ----------------

    private fun showLauncherSheet() {
        val dialog = BottomSheetDialog(this, R.style.Theme_NetSniffer_BottomSheet)
        val view = layoutInflater.inflate(R.layout.sheet_launcher, null)
        dialog.setContentView(view)

        val tabBrowser = view.findViewById<Button>(R.id.tabBrowserBtn)
        val tabAviator = view.findViewById<Button>(R.id.tabAviatorBtn)
        val tabWinGo = view.findViewById<Button>(R.id.tabWinGoBtn)
        val tabAutoBet = view.findViewById<Button>(R.id.tabAutoBetBtn)
        val tabProfile = view.findViewById<Button>(R.id.tabProfileBtn)
        val tabNetwork = view.findViewById<Button>(R.id.tabNetworkBtn)
        val urlInput = view.findViewById<EditText>(R.id.sheetUrlInput)
        val goBtn = view.findViewById<Button>(R.id.sheetGoBtn)
        val relayToggle = view.findViewById<SwitchCompat>(R.id.sheetRelayToggle)
        val relayInput = view.findViewById<EditText>(R.id.sheetRelayInput)

        // Order MUST match PagerAdapter positions: 0 Browser, 1 Aviator, 2 WinGo, 3 AutoBet, 4 Profile, 5 Network
        val tabs = listOf(tabBrowser, tabAviator, tabWinGo, tabAutoBet, tabProfile, tabNetwork)
        fun selectTab(idx: Int) {
            tabs.forEachIndexed { i, btn -> btn.isSelected = i == idx }
            b.pager.setCurrentItem(idx, true)
            prefs.edit().putInt(PREF_LAST_TAB, idx).apply()
        }
        selectTab(b.pager.currentItem)
        tabs.forEachIndexed { i, btn -> btn.setOnClickListener { selectTab(i) } }

        // Prefill browser state
        val browser = getBrowserFragment()
        urlInput.setText(browser?.currentUrl().orEmpty())
        relayToggle.isChecked = browser?.relayEnabled() ?: prefs.getBoolean("ws_relay_enabled", true)
        relayInput.setText(browser?.relayUrl() ?: prefs.getString("ws_relay_url", "").orEmpty())

        val submitUrl = {
            b.pager.setCurrentItem(0, false)
            getBrowserFragment()?.navigateTo(urlInput.text.toString())
            dialog.dismiss()
        }
        goBtn.setOnClickListener { submitUrl() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { submitUrl(); true } else false
        }

        val applyRelay = {
            getBrowserFragment()?.setRelay(relayToggle.isChecked, relayInput.text.toString())
        }
        relayToggle.setOnCheckedChangeListener { _, _ -> applyRelay() }
        relayInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) applyRelay() }
        relayInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { applyRelay(); true } else false
        }

        // Simple entry animation on the sheet content
        view.alpha = 0f
        view.translationY = 40f
        view.animate().alpha(1f).translationY(0f).setDuration(220).start()

        dialog.show()
    }

    private fun getBrowserFragment(): BrowserFragment? {
        // ViewPager2 with FragmentStateAdapter tags fragments as "f<id>"
        return supportFragmentManager.findFragmentByTag("f0") as? BrowserFragment
    }

    /** Called from LicenseLockOverlay's REGISTER NOW button. */
    fun openRegisterInBrowser(url: String) {
        b.pager.setCurrentItem(0, false)
        prefs.edit().putInt(PREF_LAST_TAB, 0).apply()
        b.pager.post {
            getBrowserFragment()?.clearAllDataAndNavigate(url)
        }
    }

    /** Switch to the DK Win (browser) tab without wiping session — used by "please login first" overlay. */
    fun switchToBrowserTab() {
        b.pager.setCurrentItem(0, true)
        prefs.edit().putInt(PREF_LAST_TAB, 0).apply()
    }




    override fun onBackPressed() {
        // On Browser tab: let WebView walk its own history first
        if (b.pager.currentItem == 0) {
            val browser = getBrowserFragment()
            if (browser?.handleBackPressed() == true) return
            super.onBackPressed()
            return
        }
        // Any other tab → return to Browser tab
        b.pager.setCurrentItem(0, true)
    }


    // ---------------- Notification bell ----------------

    private fun setupNotificationBell() {
        val bell = b.bellWrap
        val badge = b.bellBadge

        // Push current DK Win uid into the store whenever the deposit gate learns one.
        DepositGate.state.observe(this) { st ->
            if (!st?.uid.isNullOrBlank()) NotificationStore.setUid(st!!.uid)
        }

        NotificationStore.unreadCount.observe(this) { n ->
            val count = n ?: 0
            if (count <= 0) {
                badge.visibility = View.GONE
            } else {
                badge.visibility = View.VISIBLE
                badge.text = if (count > 9) "9+" else count.toString()
            }
        }

        bell.setOnClickListener { showNotificationSheet() }
    }

    private fun showNotificationSheet() {
        val dialog = BottomSheetDialog(this, R.style.Theme_NetSniffer_BottomSheet)
        val ctx = this
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF0B1220.toInt())
        }

        // Header row
        val header = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(android.widget.TextView(ctx).apply {
            text = "🔔  Notifications"
            setTextColor(0xFFFCD34D.toInt())
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val markAll = android.widget.TextView(ctx).apply {
            text = "✓ পড়া শেষ"
            setTextColor(0xFF60A5FA.toInt())
            textSize = 12f
            setPadding(pad / 2, pad / 3, pad / 2, pad / 3)
        }
        header.addView(markAll)
        root.addView(header)

        // Divider
        root.addView(View(ctx).apply {
            setBackgroundColor(0x22FFFFFF)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * resources.displayMetrics.density).toInt(),
            ).apply { topMargin = pad / 2; bottomMargin = pad / 2 }
        })

        val listWrap = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(listWrap) }
        root.addView(scroll, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ))

        fun render(items: List<NotificationStore.Item>) {
            listWrap.removeAllViews()
            if (items.isEmpty()) {
                listWrap.addView(android.widget.TextView(ctx).apply {
                    text = "কোনো নতুন notification নেই।"
                    setTextColor(0xFF94A3B8.toInt())
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, pad * 2, 0, pad * 2)
                })
                return
            }
            for (item in items) {
                val card = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 12f * resources.displayMetrics.density
                        setColor(0x14FFFFFF)
                        setStroke((1 * resources.displayMetrics.density).toInt(), 0x2260A5FA.toInt())
                    }
                }
                card.addView(android.widget.TextView(ctx).apply {
                    text = item.title
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                card.addView(android.widget.TextView(ctx).apply {
                    text = item.body
                    setTextColor(0xFFCBD5E1.toInt())
                    textSize = 13f
                    setPadding(0, pad / 4, 0, 0)
                })
                card.addView(android.widget.TextView(ctx).apply {
                    text = item.createdAt.take(19).replace('T', ' ')
                    setTextColor(0xFF64748B.toInt())
                    textSize = 10.5f
                    setPadding(0, pad / 3, 0, 0)
                })
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = pad / 2 }
                listWrap.addView(card, lp)
            }
        }

        NotificationStore.items.observe(this) { render(it ?: emptyList()) }
        render(NotificationStore.items.value ?: emptyList())

        markAll.setOnClickListener {
            NotificationStore.markAllRead()
        }

        // Auto-mark-as-read when the sheet actually opens.
        dialog.setOnShowListener { NotificationStore.markAllRead() }
        dialog.setContentView(root)
        dialog.show()
    }

    companion object {
        private const val PREF_FAB_X = "fab_x"
        private const val PREF_FAB_Y = "fab_y"
        private const val PREF_LAST_TAB = "last_tab"
        private const val TELEGRAM_CHANNEL_USERNAME = "Eip_trader"
        private const val TELEGRAM_CHANNEL_URL = "https://t.me/Eip_trader"
        private const val RECHARGE_HISTORY_URL = "https://dkwin8.com/#/wallet/RechargeHistory"
    }
}
