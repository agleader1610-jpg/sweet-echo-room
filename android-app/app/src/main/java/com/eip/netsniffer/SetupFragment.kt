package com.eip.netsniffer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
            openVideoSafely(url)
        }
    }

    private fun openVideoSafely(url: String) {
        val ctx = context ?: return
        val uri = Uri.parse(url)

        val videoIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        try {
            startActivity(Intent.createChooser(videoIntent, "ভিডিও দেখুন"))
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(browserIntent)
            } catch (_: ActivityNotFoundException) {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Setup video", url))
                Toast.makeText(ctx, "ভিডিও লিংক কপি হয়েছে — browser-এ paste করুন", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            try {
                startActivity(browserIntent)
            } catch (_: Exception) {
                if (ctx is Activity) ctx.runOnUiThread {
                    Toast.makeText(ctx, "ভিডিও খুলতে সমস্যা হচ্ছে। একটু পরে আবার চেষ্টা করুন।", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
