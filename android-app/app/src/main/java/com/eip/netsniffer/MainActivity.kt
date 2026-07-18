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
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.pager.adapter = PagerAdapter(this)
        b.pager.offscreenPageLimit = 3
        b.pager.isUserInputEnabled = false
        b.pager.setCurrentItem(prefs.getInt(PREF_LAST_TAB, 0), false)

        setupDraggableFab()

        // Remote force-update gate (fail-open: silent on any failure).
        VersionChecker.check(this)

        // Telegram channel invite on app open.
        b.root.post { showTelegramJoinDialog() }
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


    companion object {
        private const val PREF_FAB_X = "fab_x"
        private const val PREF_FAB_Y = "fab_y"
        private const val PREF_LAST_TAB = "last_tab"
        private const val TELEGRAM_CHANNEL_USERNAME = "Eip_trader"
        private const val TELEGRAM_CHANNEL_URL = "https://t.me/Eip_trader"
    }
}
