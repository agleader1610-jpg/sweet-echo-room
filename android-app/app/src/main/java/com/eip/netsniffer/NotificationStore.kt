package com.eip.netsniffer

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * NotificationStore — pulls broadcast + targeted notifications from the
 * backend, tracks which ones the user has read locally, and exposes:
 *   - items      : LiveData<List<Item>>   newest first
 *   - unreadCount: LiveData<Int>          drives the bell badge
 *
 * Reads are tracked in SharedPreferences (a set of id strings), so the
 * bell doesn't nag once the user has opened the panel.
 */
object NotificationStore {
    private const val TAG = "NotifStore"
    private const val PREFS = "eip_notifs"
    private const val KEY_READ_IDS = "read_ids"
    private val URL_STR: String by lazy { buildString {
        append("htt"); append("ps://")
        append("api"); append("apk").append(".eip").append(".").append("lat")
        append("/a"); append("pi/"); append("pub"); append("lic/")
        append("list-not"); append("ifications")
    } }

    data class Item(
        val id: String,
        val title: String,
        val body: String,
        val targetUid: String?,
        val createdAt: String,
    )

    val items = MutableLiveData<List<Item>>(emptyList())
    val unreadCount = MutableLiveData(0)

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private var started = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var currentUid: String? = null

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (started) return
        started = true
        exec.scheduleWithFixedDelay({ safeFetch() }, 2, 60, TimeUnit.SECONDS)
    }

    /** Called when we learn / update the DK Win user id (also triggers a fetch). */
    fun setUid(uid: String?) {
        currentUid = uid?.takeIf { it.isNotBlank() }
        safeFetch()
    }

    fun markAllRead() {
        val ctx = appCtx ?: return
        val ids = items.value.orEmpty().map { it.id }.toSet()
        val prev = readIds(ctx)
        val merged = prev + ids
        prefs(ctx).edit().putStringSet(KEY_READ_IDS, merged).apply()
        recomputeUnread()
    }

    private fun safeFetch() {
        val ctx = appCtx ?: return
        exec.execute {
            try { fetch(ctx) } catch (t: Throwable) { Log.w(TAG, "fetch failed: ${t.message}") }
        }
    }

    private fun fetch(ctx: Context) {
        val conn = URL(URL_STR).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val body = JSONObject().apply {
                val uid = currentUid
                if (!uid.isNullOrBlank()) put("uid", uid)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) return
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(txt)
            val arr: JSONArray = root.optJSONArray("items") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Item(
                    id        = o.optString("id"),
                    title     = o.optString("title"),
                    body      = o.optString("body"),
                    targetUid = o.optString("target_uid").takeIf { it.isNotBlank() && it != "null" },
                    createdAt = o.optString("created_at"),
                )
            }
            items.postValue(list)
            recomputeUnread(list)
            // Prune read set to only ids we still know about.
            val known = list.map { it.id }.toSet()
            val kept = readIds(ctx).intersect(known)
            if (kept.size != readIds(ctx).size) {
                prefs(ctx).edit().putStringSet(KEY_READ_IDS, kept).apply()
            }
        } finally { conn.disconnect() }
    }

    private fun recomputeUnread(list: List<Item>? = items.value) {
        val ctx = appCtx ?: return
        val ids = readIds(ctx)
        unreadCount.postValue((list ?: emptyList()).count { it.id !in ids })
    }

    private fun readIds(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_READ_IDS, emptySet()) ?: emptySet()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
