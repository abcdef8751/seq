package com.rp.seq.eq

import android.content.Context
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Session discovery via binder dump of the audio service — same data source
 * RootlessJamesDSP uses (AudioServiceDumpProvider / DumpUtils.dumpAll).
 *
 * Requires android.permission.DUMP (signature|privileged|development).
 * On Waydroid/userdebug:
 *   adb shell pm grant com.rp.seq android.permission.DUMP
 */
object SessionDumper {
    private const val TAG = "seq-eq-dump"

    data class Session(val sid: Int, val uid: Int, val usage: String)

    fun dump(context: Context): List<Session> {
        // Prefer "audio" (AudioService) — has sessionId on API 31+
        val text = dumpService("audio")
            ?: dumpService("media.audio_policy")
            ?: return emptyList()
        val fromAudio = parseAudioService(text)
        if (fromAudio.isNotEmpty()) return fromAudio

        // Fallback: AudioFlinger global session refs (sid + pid only)
        val flinger = dumpService("media.audio_flinger") ?: return emptyList()
        return parseAudioFlinger(flinger)
    }

    private fun dumpService(name: String): String? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, name) as? IBinder ?: return null
            val pipe = ParcelFileDescriptor.createPipe()
            val readPipe = pipe[0]
            val writePipe = pipe[1]
            binder.dumpAsync(writePipe.fileDescriptor, emptyArray())
            writePipe.close()
            val text = FileInputStream(readPipe.fileDescriptor).use { fis ->
                InputStreamReader(fis, Charsets.UTF_8).use { it.readText() }
            }
            readPipe.close()
            text
        } catch (e: Exception) {
            Log.w(TAG, "dump '$name' failed: ${e.message}")
            null
        }
    }

    /** RootlessJamesDSP AudioServiceDumpProvider regex (API 31+) */
    private fun parseAudioService(dump: String): List<Session> {
        val re31 =
            Regex("""AudioPlaybackConfiguration.*u/pid:(\d+)/(\d+).*usage=(\w+).*sessionId:(\d+)""")
        val out = ArrayList<Session>()
        for (m in re31.findAll(dump)) {
            val uid = m.groupValues[1].toIntOrNull() ?: continue
            val usage = m.groupValues[3]
            val sid = m.groupValues[4].toIntOrNull() ?: continue
            if (sid <= 0) continue
            if (!isRecordableUsage(usage)) continue
            out.add(Session(sid, uid, usage))
        }
        return out.distinctBy { it.sid }
    }

    private fun parseAudioFlinger(dump: String): List<Session> {
        // "session  cnt     pid    uid  name" then lines of numbers
        val re = Regex("""^\s*(\d+)\s+\d+\s+(\d+)\s+(\d+)\s+""", RegexOption.MULTILINE)
        val out = ArrayList<Session>()
        for (m in re.findAll(dump)) {
            val sid = m.groupValues[1].toIntOrNull() ?: continue
            val uid = m.groupValues[3].toIntOrNull() ?: continue
            if (sid <= 0) continue
            out.add(Session(sid, uid, "USAGE_UNKNOWN"))
        }
        return out.distinctBy { it.sid }
    }

    private fun isRecordableUsage(usage: String): Boolean {
        val u = usage.uppercase()
        return u.contains("MEDIA") || u.contains("GAME") || u.contains("UNKNOWN")
    }
}
