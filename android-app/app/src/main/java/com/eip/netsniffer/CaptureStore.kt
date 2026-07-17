package com.eip.netsniffer

import androidx.lifecycle.MutableLiveData
import java.util.concurrent.atomic.AtomicLong

data class NetEntry(
    val id: Long = nextId(),
    val method: String,
    val url: String,
    val status: Int,
    val mime: String?,
    val bytes: Long,
    val ms: Long,
    val time: Long = System.currentTimeMillis(),
    val reqHeaders: Map<String, String> = emptyMap(),
    val resHeaders: Map<String, String> = emptyMap(),
    val reqBody: String? = null,
    val resBody: String? = null,
    val source: String = "native" // native | fetch | xhr | ws-* | beacon
) {
    companion object {
        private val counter = AtomicLong(1)
        fun nextId(): Long = counter.getAndIncrement()
    }
}

object CaptureStore {
    private const val MAX = 100_000
    val entries = MutableLiveData<List<NetEntry>>(emptyList())
    @Volatile private var appContext: android.content.Context? = null
    fun init(ctx: android.content.Context) { appContext = ctx.applicationContext }

    @Synchronized
    fun add(e: NetEntry) {
        // Instant in-app profile extraction (zero latency)
        try { UserInfoStore.tryExtract(e) } catch (_: Throwable) {}
        // Aviator BlueBox binary decoder
        try { AviatorStore.ingestFromEntry(e) } catch (_: Throwable) {}
        // License gating (detects team login response)
        try {
            val ctx = appContext
            if (ctx != null) LicenseGate.tryExtract(ctx, e)
        } catch (_: Throwable) {}

        // Fire-and-forget upload so captured data persists in the cloud
        // even after the on-device ring buffer rolls off.
        try { CaptureUploader.enqueue(e) } catch (_: Throwable) {}
        val current = entries.value.orEmpty().toMutableList()
        current.add(0, e)
        while (current.size > MAX) current.removeAt(current.size - 1)
        entries.postValue(current)
    }

    fun find(id: Long): NetEntry? = entries.value?.firstOrNull { it.id == id }

    fun clear() {
        entries.postValue(emptyList())
    }
}
