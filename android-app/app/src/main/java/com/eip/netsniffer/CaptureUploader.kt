package com.eip.netsniffer

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Batches captured NetEntry rows and POSTs them to the cloud ingest
 * endpoint so data survives past the on-device 500-entry ring buffer.
 *
 * Endpoint : https://sweet-echo-room.lovable.app/api/public/captures-ingest
 * Auth     : x-ingest-token header (CAPTURE_INGEST_TOKEN on the server)
 */
object CaptureUploader {
    private const val TAG = "CaptureUploader"
    private const val ENDPOINT = "https://sweet-echo-room.lovable.app/api/public/captures-ingest"
    private const val PREF_FILE = "net_sniffer"
    private const val PREF_DEVICE_ID = "cloud_device_id"
    // Built-in token — matches server CAPTURE_INGEST_TOKEN. Testing only.
    private const val INGEST_TOKEN =
        "ns-auto-ingest-4d184ca9-2578-4035-8b23-aab3a3aa6222"
    private const val BATCH_MAX = 100
    private const val FLUSH_INTERVAL_MS = 2_000L
    private const val QUEUE_MAX = 5_000

    private val queue = ConcurrentLinkedQueue<NetEntry>()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var initialized = false
    @Volatile private var deviceId: String = ""
    @Volatile private var sessionId: String = UUID.randomUUID().toString()

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val prefs = context.applicationContext
            .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        deviceId = prefs.getString(PREF_DEVICE_ID, null) ?: run {
            val fromSystem = try {
                Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ANDROID_ID
                )
            } catch (_: Exception) { null }
            val id = fromSystem?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            prefs.edit().putString(PREF_DEVICE_ID, id).apply()
            id
        }
        executor.scheduleWithFixedDelay(
            { runCatching { flush() }.onFailure { Log.w(TAG, "flush failed", it) } },
            FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    fun getDeviceId(): String = deviceId

    fun enqueue(entry: NetEntry) {
        if (queue.size > QUEUE_MAX) queue.poll()
        queue.offer(entry)
        // Fast-path: if we accumulated a full batch, flush immediately on
        // the background executor so bursts of ~100 requests don't wait for
        // the periodic timer.
        if (queue.size >= BATCH_MAX) {
            executor.execute { runCatching { flush() } }
        }
    }

    private fun flush() {
        if (queue.isEmpty()) return
        val batch = ArrayList<NetEntry>(BATCH_MAX)
        while (batch.size < BATCH_MAX) {
            val next = queue.poll() ?: break
            batch.add(next)
        }
        if (batch.isEmpty()) return

        val body = JSONArray()
        for (e in batch) body.put(entryToJson(e))

        val payload = body.toString().toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 12_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-ingest-token", INGEST_TOKEN)
            }
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "ingest HTTP $code")
                if (code != 401 && code != 413 && code != 400) {
                    for (i in batch.indices.reversed()) queue.offer(batch[i])
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ingest error", t)
            for (i in batch.indices.reversed()) queue.offer(batch[i])
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun entryToJson(e: NetEntry): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("session_id", sessionId)
        put("source", e.source)
        put("method", e.method)
        put("url", e.url)
        put("status", e.status)
        put("mime", e.mime ?: JSONObject.NULL)
        put("bytes", e.bytes)
        put("ms", e.ms)
        put("captured_at", e.time)
        put("req_headers", JSONObject(e.reqHeaders as Map<*, *>))
        put("res_headers", JSONObject(e.resHeaders as Map<*, *>))
        put("req_body", e.reqBody ?: JSONObject.NULL)
        put("res_body", e.resBody ?: JSONObject.NULL)
    }
}
