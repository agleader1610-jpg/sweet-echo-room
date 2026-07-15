package com.eip.netsniffer

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

open class SniffingWebViewClient(
    private val hookScriptProvider: () -> String = { JsHook.script() }
) : WebViewClient() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
        val url = req.url?.toString() ?: return null
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return null

        val start = System.currentTimeMillis()
        return try {
            val builder = Request.Builder().url(url).method(req.method, null)
            req.requestHeaders.forEach { (k, v) ->
                try { builder.header(k, v) } catch (_: Exception) {}
            }
            val resp = client.newCall(builder.build()).execute()
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val ctype = resp.header("content-type") ?: "application/octet-stream"
            val mime = ctype.substringBefore(";").trim().ifEmpty { "application/octet-stream" }
            val charset = ctype.substringAfter("charset=", "utf-8").trim().ifEmpty { "utf-8" }

            val resHeaders = mutableMapOf<String, String>()
            resp.headers.forEach { (k, v) -> resHeaders[k] = v }

            val preview: String? = try {
                when {
                    bytes.isEmpty() -> "(empty body)"
                    isTextLike(mime) -> {
                        val limit = minOf(bytes.size, 256 * 1024)
                        val head = String(bytes, 0, limit, charset(charset))
                        if (bytes.size > limit) head + "\n\n… [truncated, ${bytes.size - limit} more bytes]" else head
                    }
                    else -> {
                        val limit = minOf(bytes.size, 256 * 1024)
                        val b64 = android.util.Base64.encodeToString(bytes, 0, limit, android.util.Base64.NO_WRAP)
                        val hex = bytes.take(minOf(bytes.size, 512))
                            .joinToString(" ") { String.format("%02X", it) }
                        buildString {
                            append("[Binary ").append(bytes.size).append(" bytes • mime=").append(mime).append("]\n\n")
                            append("── HEX (first ").append(minOf(bytes.size, 512)).append(" bytes) ──\n")
                            append(hex).append("\n\n")
                            append("── BASE64 (first ").append(limit).append(" bytes) ──\n")
                            append(b64)
                            if (bytes.size > limit) append("\n\n… [truncated, ${bytes.size - limit} more bytes]")
                        }
                    }
                }
            } catch (_: Exception) { null }

            CaptureStore.add(
                NetEntry(
                    method = req.method,
                    url = url,
                    status = resp.code,
                    mime = mime,
                    bytes = bytes.size.toLong(),
                    ms = System.currentTimeMillis() - start,
                    reqHeaders = req.requestHeaders?.toMap() ?: emptyMap(),
                    resHeaders = resHeaders,
                    resBody = preview,
                    source = "native"
                )
            )

            val filtered = resHeaders.filterKeys {
                val lk = it.lowercase()
                lk !in setOf("content-encoding", "transfer-encoding", "connection", "content-length")
            }

            // Inject NetSniffer hook into HTML documents so it runs BEFORE page scripts.
            val outBytes: ByteArray = if (mime.contains("html", ignoreCase = true) && bytes.isNotEmpty()) {
                try {
                    val html = String(bytes, charset(charset))
                    val tag = "<script>${hookScriptProvider().replace("</script", "<\\/script", ignoreCase = true)}</script>"
                    val injected = when {
                        html.contains("<head", ignoreCase = true) ->
                            html.replaceFirst(Regex("(?i)<head[^>]*>"), "$0$tag")
                        html.contains("<html", ignoreCase = true) ->
                            html.replaceFirst(Regex("(?i)<html[^>]*>"), "$0$tag")
                        else -> tag + html
                    }
                    injected.toByteArray(charset(charset))
                } catch (_: Exception) { bytes }
            } else bytes

            WebResourceResponse(
                mime,
                charset,
                resp.code,
                resp.message.ifEmpty { "OK" },
                filtered,
                ByteArrayInputStream(outBytes)
            )
        } catch (e: Exception) {
            CaptureStore.add(
                NetEntry(
                    method = req.method,
                    url = url,
                    status = -1,
                    mime = e.javaClass.simpleName,
                    bytes = 0,
                    ms = System.currentTimeMillis() - start,
                    reqHeaders = req.requestHeaders?.toMap() ?: emptyMap(),
                    resBody = e.message,
                    source = "native"
                )
            )
            null
        }
    }

    private fun isTextLike(mime: String): Boolean {
        val m = mime.lowercase()
        return m.startsWith("text/") ||
            m.contains("json") || m.contains("xml") ||
            m.contains("javascript") || m.contains("html") ||
            m.contains("css") || m.contains("form-urlencoded")
    }
}
