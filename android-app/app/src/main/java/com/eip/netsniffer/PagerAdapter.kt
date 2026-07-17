package com.eip.netsniffer

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class PagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount() = 5
    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> BrowserFragment()
        1 -> AviatorFragment()
        2 -> WinGoFragment()
        3 -> ProfileFragment()
        else -> NetworkFragment()
    }
}
