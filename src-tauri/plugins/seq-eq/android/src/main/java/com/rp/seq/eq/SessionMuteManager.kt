package com.rp.seq.eq

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Mute original app streams so only our processed AudioTrack is audible.
 *
 * RootlessJamesDSP MutedAudioEffectFactory primary path:
 *   DynamicsProcessing(priority=MAX, sessionId) + setInputGainAllChannelsTo(-200f)
 * plus re-enable listeners when another app steals control.
 */
class SessionMuteManager {
    constructor(@Suppress("UNUSED_PARAMETER") context: android.content.Context)

    private val effects = ConcurrentHashMap<Int, AudioEffect>()
    private val excludedUids = mutableSetOf<Int>()
    private val sidToUid = ConcurrentHashMap<Int, Int>()

    fun setExcludedUids(uids: Collection<Int>) {
        excludedUids.clear()
        excludedUids.addAll(uids)
        for ((sid, uid) in sidToUid) {
            if (uid in excludedUids) release(sid)
        }
    }

    fun syncSessions(sessions: Map<Int, Int>) {
        // sessions: sid -> uid
        for ((sid, uid) in sessions) {
            sidToUid[sid] = uid
        }
        val wanted = sessions.filter { (_, uid) -> uid !in excludedUids }.keys
        for (sid in effects.keys.filter { it !in wanted }) {
            // keep effect-broadcast-only sessions (uid == -1) if still present
            val uid = sidToUid[sid]
            if (uid != null && uid >= 0 && sid !in wanted) {
                release(sid)
            }
        }
        for ((sid, uid) in sessions) {
            if (uid in excludedUids) continue
            // RJDSP: force-allow capture for every session uid so the recorder gets audio.
            if (ShizukuPermissions.hasShizukuPermission()) {
                ShizukuPermissions.setAllowedCapturePolicy(uid, allowByMediaProjection = true)
            }
            if (!effects.containsKey(sid)) attach(sid)
        }
    }

    fun addSession(sid: Int, uid: Int) {
        if (sid <= 0) return
        if (uid >= 0) {
            if (uid in excludedUids) return
            sidToUid[sid] = uid
            // RJDSP: force-allow capture so the session's audio reaches our recorder.
            // Without this, restricted apps are muted but not captured → silence.
            if (ShizukuPermissions.hasShizukuPermission()) {
                ShizukuPermissions.setAllowedCapturePolicy(uid, allowByMediaProjection = true)
            }
        }
        if (!effects.containsKey(sid)) attach(sid)
    }

    fun clear() {
        for (sid in effects.keys.toList()) release(sid)
        sidToUid.clear()
    }

    private fun attach(sid: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.e(TAG, "DynamicsProcessing requires API 28+")
            return
        }
        try {
            // RJDSP: DynamicsProcessing(Int.MAX_VALUE, sid, null)
            val dp = DynamicsProcessing(Int.MAX_VALUE, sid, null)
            dp.setInputGainAllChannelsTo(-200f)
            dp.enabled = true
            dp.setEnableStatusListener { effect, enabled ->
                if (!enabled) {
                    try {
                        if (effect is DynamicsProcessing) {
                            effect.setInputGainAllChannelsTo(-200f)
                        }
                        effect.enabled = true
                        Log.d(TAG, "re-enabled mute session $sid")
                    } catch (e: Exception) {
                        Log.w(TAG, "lost mute control session $sid: ${e.message}")
                        release(sid)
                    }
                }
            }
            dp.setControlStatusListener { effect, granted ->
                if (!granted) {
                    Log.w(TAG, "mute control taken session $sid")
                    release(sid)
                } else {
                    try {
                        if (effect is DynamicsProcessing) {
                            effect.setInputGainAllChannelsTo(-200f)
                        }
                        effect.enabled = true
                    } catch (e: Exception) {
                        Log.w(TAG, "failed regain mute session $sid: ${e.message}")
                        release(sid)
                    }
                }
            }
            effects[sid] = dp
            Log.i(TAG, "muted session $sid")
        } catch (e: Exception) {
            Log.e(TAG, "mute attach failed sid=$sid: ${e.message}")
        }
    }

    private fun release(sid: Int) {
        effects.remove(sid)?.let { fx ->
            try {
                fx.enabled = false
                fx.release()
            } catch (_: Exception) {
            }
            Log.i(TAG, "released mute session $sid")
        }
    }

    companion object {
        private const val TAG = "seq-eq-mute"
    }
}
