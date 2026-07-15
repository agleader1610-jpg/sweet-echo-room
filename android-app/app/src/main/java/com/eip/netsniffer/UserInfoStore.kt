package com.eip.netsniffer

import androidx.lifecycle.MutableLiveData
import org.json.JSONObject

/**
 * In-app instant profile extraction from captured JSON responses.
 * Zero-latency: parsed synchronously as each NetEntry is added.
 */
data class UserProfile(
    val uid: String? = null,
    val userName: String? = null,
    val nickName: String? = null,
    val phone: String? = null,
    val balance: String? = null,
    val amountOfCode: String? = null,
    val host: String? = null,
    val sourceUrl: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val raw: String? = null,
)

object UserInfoStore {
    val current = MutableLiveData<UserProfile?>(null)
    val history = MutableLiveData<List<UserProfile>>(emptyList())
    private const val MAX_HISTORY = 200

    @Synchronized
    fun tryExtract(entry: NetEntry) {
        val body = entry.resBody ?: return
        if (body.length < 2 || body.firstOrNull() != '{') return
        val obj = try { JSONObject(body) } catch (_: Throwable) { return }
        val d = obj.optJSONObject("data") ?: obj

        val uid = d.firstNonEmpty("userId", "uid", "user_id", "memberId")
        val user = d.firstNonEmpty("userName", "username", "account", "loginName")
        val nick = d.firstNonEmpty("nickName", "nickname", "name")
        val phone = d.firstNonEmpty("phone", "mobile", "phoneNumber")
        val balance = d.firstNonEmpty("amount", "balance", "money")
        val code = d.firstNonEmpty("amountofCode", "amountOfCode")

        if (uid == null && user == null && nick == null && balance == null) return

        val prev = current.value
        val merged = UserProfile(
            uid = uid ?: prev?.uid,
            userName = user ?: prev?.userName,
            nickName = nick ?: prev?.nickName,
            phone = phone ?: prev?.phone,
            balance = balance ?: prev?.balance,
            amountOfCode = code ?: prev?.amountOfCode,
            host = entry.url.substringAfter("//").substringBefore("/"),
            sourceUrl = entry.url,
            raw = d.toString(),
        )
        current.postValue(merged)
        val snapshot = UserProfile(
            uid = uid, userName = user, nickName = nick, phone = phone,
            balance = balance, amountOfCode = code,
            host = merged.host, sourceUrl = entry.url, raw = d.toString(),
        )
        val list = history.value.orEmpty().toMutableList()
        list.add(0, snapshot)
        while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        history.postValue(list)
    }

    fun clear() {
        current.postValue(null)
        history.postValue(emptyList())
    }

    private fun JSONObject.firstNonEmpty(vararg keys: String): String? {
        for (k in keys) {
            if (!has(k) || isNull(k)) continue
            val v = opt(k)?.toString()?.trim().orEmpty()
            if (v.isNotEmpty()) return v
        }
        return null
    }
}
