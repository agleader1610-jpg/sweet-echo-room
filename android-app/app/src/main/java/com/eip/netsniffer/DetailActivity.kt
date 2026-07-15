package com.eip.netsniffer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eip.netsniffer.databinding.ActivityDetailBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DetailActivity : AppCompatActivity() {
    private lateinit var b: ActivityDetailBinding

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        b = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val e = CaptureStore.find(id)
        val container = b.sections
        if (e == null) {
            container.addView(sectionCard("Error", "Entry not found (may have been cleared)."))
            return
        }

        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(e.time))
        val statusLabel = when {
            e.status < 0 -> "ERROR"
            e.status == 0 -> "—"
            else -> e.status.toString()
        }

        val general = linkedMapOf(
            "Method" to e.method,
            "Status" to statusLabel,
            "Source" to e.source,
            "MIME" to (e.mime ?: "—"),
            "Size" to formatBytes(e.bytes),
            "Time" to "${e.ms} ms",
            "When" to timeStr,
            "URL" to e.url,
        )

        container.addView(sectionCard("General", keyValueBlock(general)))
        container.addView(sectionCard("Request Headers", headersBlock(e.reqHeaders)))
        container.addView(sectionCard("Request Body", bodyBlock(e.reqBody, e.mime, isRequest = true)))
        container.addView(sectionCard("Response Headers", headersBlock(e.resHeaders)))
        container.addView(sectionCard("Response Body", bodyBlock(e.resBody, e.mime, isRequest = false)))

        val fullText = buildFullText(e, timeStr, statusLabel)
        b.copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NetSniffer entry", fullText))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        b.shareBtn.setOnClickListener {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, fullText)
            }
            startActivity(Intent.createChooser(i, "Share entry"))
        }
    }

    // ---------- UI builders ----------

    private fun sectionCard(title: String, contentView: View): View {
        val ctx = this
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            layoutParams = lp
            setPadding(0, 0, 0, dp(4))
            elevation = dp(1).toFloat()
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#EEF3F8"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(ctx).apply {
            text = title
            setTextColor(Color.parseColor("#1565C0"))
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val copy = Button(ctx).apply {
            text = "Copy"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener {
                val txt = extractText(contentView)
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(title, txt))
                Toast.makeText(this@DetailActivity, "$title copied", Toast.LENGTH_SHORT).show()
            }
        }
        header.addView(titleTv)
        header.addView(copy)
        card.addView(header)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        body.addView(contentView)
        card.addView(body)
        return card
    }

    private fun sectionCard(title: String, text: String): View =
        sectionCard(title, monoText(text))

    private fun keyValueBlock(map: Map<String, String>): View {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        map.forEach { (k, v) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(2), 0, dp(2))
            }
            val key = TextView(this).apply {
                text = k
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(Color.parseColor("#455A64"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val value = TextView(this).apply {
                text = v
                setTypeface(Typeface.MONOSPACE)
                setTextColor(Color.parseColor("#212121"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextIsSelectable(true)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(key); row.addView(value)
            ll.addView(row)
        }
        return ll
    }

    private fun headersBlock(h: Map<String, String>): View {
        if (h.isEmpty()) return monoText("(none)", muted = true)
        return keyValueBlock(LinkedHashMap(h))
    }

    private fun bodyBlock(body: String?, mime: String?, isRequest: Boolean): View {
        if (body.isNullOrEmpty()) {
            val msg = if (isRequest) "(no body)"
                      else "Not captured: binary or too large"
            return monoText(msg, muted = true)
        }
        val pretty = tryPrettyJson(body, mime)
        return monoText(pretty)
    }

    private fun monoText(txt: String, muted: Boolean = false): TextView {
        return TextView(this).apply {
            text = txt
            setTypeface(Typeface.MONOSPACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (muted) Color.parseColor("#9E9E9E") else Color.parseColor("#212121"))
            setTextIsSelectable(true)
            setHorizontallyScrolling(false)
        }
    }

    private fun extractText(v: View): String {
        val sb = StringBuilder()
        when (v) {
            is TextView -> sb.append(v.text)
            is ViewGroup -> {
                for (i in 0 until v.childCount) {
                    sb.append(extractText(v.getChildAt(i)))
                    if (v.orientation() == LinearLayout.HORIZONTAL) sb.append('\t') else sb.append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun ViewGroup.orientation(): Int =
        if (this is LinearLayout) this.orientation else LinearLayout.VERTICAL

    private fun tryPrettyJson(body: String, mime: String?): String {
        val looksJson = (mime?.contains("json", true) == true) ||
            (body.trimStart().startsWith("{") || body.trimStart().startsWith("["))
        if (!looksJson) return body
        return try {
            val trimmed = body.trim()
            if (trimmed.startsWith("{"))
                org.json.JSONObject(trimmed).toString(2)
            else
                org.json.JSONArray(trimmed).toString(2)
        } catch (_: Exception) { body }
    }

    private fun formatBytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", n / (1024.0 * 1024.0))
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun buildFullText(e: NetEntry, timeStr: String, statusLabel: String): String {
        val sb = StringBuilder()
        sb.append("── GENERAL ──\n")
        sb.append("Method : ").append(e.method).append('\n')
        sb.append("URL    : ").append(e.url).append('\n')
        sb.append("Status : ").append(statusLabel).append('\n')
        sb.append("MIME   : ").append(e.mime ?: "-").append('\n')
        sb.append("Size   : ").append(formatBytes(e.bytes)).append('\n')
        sb.append("Time   : ").append(e.ms).append(" ms\n")
        sb.append("When   : ").append(timeStr).append('\n')
        sb.append("Source : ").append(e.source).append('\n')
        sb.append("\n── REQUEST HEADERS ──\n")
        if (e.reqHeaders.isEmpty()) sb.append("(none)\n")
        else e.reqHeaders.forEach { (k, v) -> sb.append(k).append(": ").append(v).append('\n') }
        sb.append("\n── REQUEST BODY ──\n")
        sb.append(e.reqBody ?: "(no body)").append('\n')
        sb.append("\n── RESPONSE HEADERS ──\n")
        if (e.resHeaders.isEmpty()) sb.append("(none)\n")
        else e.resHeaders.forEach { (k, v) -> sb.append(k).append(": ").append(v).append('\n') }
        sb.append("\n── RESPONSE BODY ──\n")
        sb.append(e.resBody ?: "Not captured: binary or too large").append('\n')
        return sb.toString()
    }

    companion object {
        const val EXTRA_ID = "entry_id"
        fun start(ctx: Context, id: Long) {
            ctx.startActivity(Intent(ctx, DetailActivity::class.java).putExtra(EXTRA_ID, id))
        }
    }
}
