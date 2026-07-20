package com.eip.netsniffer

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.VideoView
import androidx.fragment.app.Fragment

class SetupFragment : Fragment() {

    private val videoViews = mutableListOf<VideoView>()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        return i.inflate(R.layout.fragment_setup, c, false)
    }

    override fun onViewCreated(view: View, s: Bundle?) {
        setupVideo(view, R.id.videoAutoBet,
            "https://eip.lat/video_2026-07-20_18-33-27.mp4")
        setupVideo(view, R.id.videoWinGo,
            "https://eip.lat/video_2026-07-20_18-44-18.mp4")
        setupVideo(view, R.id.videoAviator,
            "https://eip.lat/video_2026-07-20_19-27-00.mp4")
    }

    private fun setupVideo(root: View, id: Int, url: String) {
        val vv = root.findViewById<VideoView>(id) ?: return
        val mc = MediaController(requireContext())
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setVideoURI(Uri.parse(url))
        vv.setOnPreparedListener { it.isLooping = false }
        videoViews.add(vv)
    }

    override fun onPause() {
        super.onPause()
        videoViews.forEach { if (it.isPlaying) it.pause() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        videoViews.forEach { runCatching { it.stopPlayback() } }
        videoViews.clear()
    }
}
