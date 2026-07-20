package com.eip.netsniffer

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class PagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    // Position 5 was Network — now replaced by SetupFragment (public release UI).
    // NetworkFragment class is retained; capture pipeline runs in the background
    // via SniffingWebViewClient + CaptureUploader (initialized in MainActivity).
    override fun getItemCount() = 6
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> BrowserFragment()
        1 -> AviatorFragment()
        2 -> WinGoFragment()
        3 -> WinGoAutoBetFragment()
        4 -> ProfileFragment()
        else -> SetupFragment()
    }
}
