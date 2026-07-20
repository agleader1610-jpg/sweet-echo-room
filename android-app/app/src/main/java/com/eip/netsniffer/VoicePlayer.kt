package com.eip.netsniffer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Plays BIG/SMALL Bengali voice cues bundled in res/raw/.
 * - Mute state persisted in SharedPreferences ("voice_muted").
 * - Prevents replay for the same period id.
 * - No microphone permission needed (media playback only).
 */
object VoicePlayer {
    private const val PREF = "net_sniffer"
    private const val KEY_MUTED = "voice_muted"
    private var lastSpokenIssue: String? = null
    private var current: MediaPlayer? = null

    fun isMuted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_MUTED, true)

    fun setMuted(ctx: Context, muted: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MUTED, muted).apply()
        if (muted) stop()
    }

    fun toggleMuted(ctx: Context): Boolean {
        val next = !isMuted(ctx)
        setMuted(ctx, next)
        return next
    }

    /** Speak once per issue. Safe to call from any signal render. */
    fun speakForIssue(ctx: Context, issue: String, pick: String) {
        if (isMuted(ctx)) return
        // Don't speak while the signal system is locked (no license / not logged in).
        // Otherwise the voice fires under the lock overlay even though no signal is visible.
        if (LicenseGate.isLocked()) return
        if (lastSpokenIssue == issue) return
        val res = when (pick.uppercase()) {
            "BIG" -> R.raw.voice_big
            "SMALL" -> R.raw.voice_small
            else -> return
        }
        lastSpokenIssue = issue
        try {
            stop()
            val mp = MediaPlayer.create(ctx.applicationContext, res) ?: return
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setOnCompletionListener { it.release(); if (current === it) current = null }
            current = mp
            mp.start()
        } catch (_: Throwable) { /* ignore playback issues */ }
    }

    private fun stop() {
        try { current?.stop() } catch (_: Throwable) {}
        try { current?.release() } catch (_: Throwable) {}
        current = null
    }
}
