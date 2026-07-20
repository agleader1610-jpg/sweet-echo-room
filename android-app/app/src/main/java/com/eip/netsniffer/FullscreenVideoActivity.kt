package com.eip.netsniffer

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

class FullscreenVideoActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_URL = "video_url"
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Immersive fullscreen
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        setContentView(R.layout.activity_fullscreen_video)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val vv = findViewById<VideoView>(R.id.fsVideo)
        val mc = MediaController(this)
        mc.setAnchorView(vv)
        vv.setMediaController(mc)
        vv.setVideoURI(Uri.parse(url))
        vv.setOnPreparedListener {
            it.isLooping = false
            vv.start()
            mc.show(0)
        }
        vv.setOnCompletionListener { finish() }
        findViewById<View>(R.id.fsClose).setOnClickListener { finish() }
    }

    override fun onPause() {
        super.onPause()
        findViewById<VideoView>(R.id.fsVideo)?.let {
            runCatching { it.stopPlayback() }
        }
        finish()
    }
}
