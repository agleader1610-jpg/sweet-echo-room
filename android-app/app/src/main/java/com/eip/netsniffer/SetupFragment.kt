package com.eip.netsniffer

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class SetupFragment : Fragment() {

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return i.inflate(R.layout.fragment_setup, c, false)
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        wire(view, R.id.playAutoBet, "https://eip.lat/video_2026-07-20_18-33-27.mp4")
        wire(view, R.id.playWinGo,   "https://eip.lat/video_2026-07-20_18-44-18.mp4")
        wire(view, R.id.playAviator, "https://eip.lat/video_2026-07-20_19-27-00.mp4")
    }

    private fun wire(root: View, id: Int, url: String) {
        root.findViewById<View>(id)?.setOnClickListener {
            val i = Intent(requireContext(), FullscreenVideoActivity::class.java)
            i.putExtra(FullscreenVideoActivity.EXTRA_URL, url)
            startActivity(i)
        }
    }
}
