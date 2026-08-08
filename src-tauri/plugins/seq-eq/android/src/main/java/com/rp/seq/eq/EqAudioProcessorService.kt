package com.rp.seq.eq

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.AudioEffect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * RootlessJamesDSP-style system EQ pipeline:
 *
 *  1. MediaProjection token → AudioPlaybackCaptureConfiguration
 *  2. AudioRecord captures other apps' media/game/unknown streams
 *  3. Parametric EQ (RBJ peaking) processes interleaved stereo PCM
 *  4. AudioTrack plays processed audio (ALLOW_CAPTURE_BY_NONE — no feedback loop)
 *  5. DynamicsProcessing mute on each active session so only our track is heard
 *
 * Session IDs come from dumpsys audio (needs DUMP on userdebug/waydroid) plus
 * AudioPlaybackCallback / OPEN_AUDIO_EFFECT_CONTROL_SESSION as poll triggers —
 * same discovery model as RootlessJamesDSP's RootlessSessionManager.
 */
class EqAudioProcessorService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var projectionData: Intent? = null
    private var recorderThread: Thread? = null
    private val disposing = AtomicBoolean(false)
    private lateinit var muteManager: SessionMuteManager
    private lateinit var audioManager: AudioManager
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engine get() = sharedEngine

    private val sessionPollRunnable = object : Runnable {
        override fun run() {
            refreshMutes()
            applyConfigIfChanged()
            if (!disposing.get() && running.get()) {
                mainHandler.postDelayed(this, SESSION_POLL_MS)
            }
        }
    }

    private var lastAppliedRevision = -1L

    /** Re-apply the persisted EQ config if the plugin bumped the revision (cross-process). */
    private fun applyConfigIfChanged() {
        val rev = prefs().getLong(KEY_REVISION, 0L)
        if (rev != lastAppliedRevision) {
            lastAppliedRevision = rev
            restoreConfig()?.let { sharedEngine.applyJson(it) }
            Log.i(TAG, "re-applied EQ config (revision=$rev)")
        }
    }

    private val effectSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION &&
                intent?.action != AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
            ) {
                return
            }
            val sid = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
            val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "?"
            Log.d(TAG, "effect session broadcast: sid=$sid pkg=$pkg action=${intent.action}")
            if (sid > 0 && intent.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
                // uid unknown from broadcast — attach mute by sid alone
                muteManager.addSession(sid, -1)
            }
            refreshMutes()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        muteManager = SessionMuteManager(this)
        ensureChannel()
        running.set(true)
        // Raise immediately so the 5s window is not exhausted before onStartCommand work.
        startAsForeground()

        // RJDSP: poll sessions when playback config changes
        playbackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                refreshMutes()
            }
        }
        audioManager.registerAudioPlaybackCallback(playbackCallback!!, mainHandler)

        ContextCompat.registerReceiver(
            this,
            effectSessionReceiver,
            IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            },
            ContextCompat.RECEIVER_EXPORTED
        )

        mainHandler.post(sessionPollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPLY -> {
                // UI changed the EQ: re-apply persisted config to the running engine.
                startAsForeground()
                restoreConfig()?.let { sharedEngine.applyJson(it) }
                return START_REDELIVER_INTENT
            }
        }

        // startForeground() MUST happen immediately (within 5s) of startForegroundService
        // and before any work that could fail/block, or the OS throws
        // "Context.startForegroundService() did not then call Service.startForeground()".
        startAsForeground()

        if (recorderThread != null) {
            return START_REDELIVER_INTENT
        }

        projectionData = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_PROJECTION, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_PROJECTION)
        }

        // Restore persisted EQ config (survives process restart / swipe-away).
        restoreConfig()?.let { sharedEngine.applyJson(it) }

        val data = projectionData
        if (data == null) {
            // RJDSP model: START_REDELIVER_INTENT redelivers the original START intent
            // (with the MediaProjection token) after a process kill, so data shouldn't be
            // null on a real swipe-restart. If it is, stay foreground (keep notification +
            // mute) and let the UI re-trigger startEngine → re-consent. Do NOT stopSelf,
            // otherwise the mute keeps running with no capture = silence.
            Log.w(TAG, "no projection data on start; waiting for redelivery/re-trigger")
            return START_REDELIVER_INTENT
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = try {
            mpm.getMediaProjection(Activity.RESULT_OK, data)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed", e)
            null
        }

        if (projection == null) {
            Log.w(TAG, "failed to acquire projection; staying foreground for re-trigger")
            return START_REDELIVER_INTENT
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        startRecording()
        return START_REDELIVER_INTENT
    }

    private fun prefs(): SharedPreferences =
        // MODE_MULTI_PROCESS: the plugin (main process) writes config, the :audio service
        // reads it. Without this, each process caches SharedPreferences in memory and the
        // :audio process never sees the main process's writes → EQ changes never apply.
        getSharedPreferences("seq_eq", Context.MODE_MULTI_PROCESS)

    private fun restoreConfig(): String? =
        prefs().getString(KEY_CONFIG, null)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!disposing.get()) {
                Log.w(TAG, "MediaProjection revoked")
                stopSelf()
            }
        }
    }

    /** Called when the user swipes the app away from recents. We're a foreground service,
     *  so we must NOT stop — processing continues in the background. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "task removed; continuing as foreground service")
    }

    override fun onDestroy() {
        disposing.set(true)
        mainHandler.removeCallbacks(sessionPollRunnable)
        playbackCallback?.let { audioManager.unregisterAudioPlaybackCallback(it) }
        try {
            unregisterReceiver(effectSessionReceiver)
        } catch (_: Exception) {
        }
        stopRecording()
        muteManager.clear()
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
        running.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(
            this, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("seq EQ")
            .setContentText("Processing system audio")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "seq EQ", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        // Match RJDSP: HAL sample rate clamped to 44.1–48 kHz
        val sampleRate = determineSampleRate().coerceIn(44100, 48000)
        val encoding = AudioFormat.ENCODING_PCM_FLOAT
        // bufferSize = number of interleaved samples (RJDSP default-ish)
        val bufferSize = max(determineHalFrames() * 2 * 4, 2048)
        val bufferSizeBytes = bufferSize * Float.SIZE_BYTES

        Log.i(TAG, "sampleRate=$sampleRate bufferSamples=$bufferSize bytes=$bufferSizeBytes")
        engine.setSampleRate(sampleRate.toFloat())
        restoreConfig()?.let { engine.applyJson(it) }

        val track: AudioTrack
        var recorder: AudioRecord
        try {
            track = buildAudioTrack(encoding, sampleRate, bufferSizeBytes)
            recorder = buildAudioRecord(encoding, sampleRate, bufferSizeBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create record/track", e)
            stopSelf()
            return
        }

        disposing.set(false)
        recorderThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val floatIn = FloatArray(bufferSize)
            val floatOut = FloatArray(bufferSize)
            try {
                recorder.startRecording()
                track.play()
                refreshMutes()

                var diagCount = 0
                var totalIn = 0
                var totalRms = 0.0
                while (!disposing.get()) {
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.startRecording()
                    }
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }

                    // RJDSP: READ_BLOCKING → process → WRITE_BLOCKING
                    val n = recorder.read(floatIn, 0, floatIn.size, AudioRecord.READ_BLOCKING)
                    if (n > 0) {
                        engine.processFloat(floatIn, floatOut, n)
                        track.write(floatOut, 0, n, AudioTrack.WRITE_BLOCKING)
                        diagCount++
                        totalIn += n
                        var sum = 0.0
                        var m = 0
                        while (m < n) { sum += floatIn[m].toDouble() * floatIn[m]; m += 2 }
                        totalRms += sum / (n / 2 + 1)
                        if (diagCount % 50 == 0) {
                            applyConfigIfChanged()
                            Log.i(TAG, "diag reads=$diagCount totalSamples=$totalIn avgRms=${"%.4f".format(totalRms / diagCount)}")
                        }
                    } else if (n < 0) {
                        Log.w(TAG, "AudioRecord.read error $n")
                        Thread.sleep(5)
                    }
                }
            } catch (e: InterruptedException) {
                // shutdown
            } catch (e: Exception) {
                Log.e(TAG, "recorder thread died", e)
                stopSelf()
            } finally {
                try {
                    if (recorder.state != AudioRecord.STATE_UNINITIALIZED) recorder.stop()
                } catch (_: Exception) {
                }
                try {
                    if (track.state != AudioTrack.STATE_UNINITIALIZED) track.stop()
                } catch (_: Exception) {
                }
                recorder.release()
                track.release()
            }
        }, "seq-eq-dsp").also { it.start() }
    }

    private fun stopRecording() {
        disposing.set(true)
        recorderThread?.interrupt()
        try {
            recorderThread?.join(500)
        } catch (_: Exception) {
        }
        recorderThread = null
    }

    private var lastDumpRegrant = 0L

    private fun refreshMutes() {
        try {
            val sessions = SessionDumper.dump(this)
            val map = LinkedHashMap<Int, Int>()
            for (s in sessions) {
                map[s.sid] = s.uid
            }
            if (debugNoMute) {
                muteManager.clear()
                Log.w(TAG, "mute SKIPPED: debugNoMute=true (${sessions.size} sessions found)")
                return
            }
            // Only mute originals when we can both discover sessions (DUMP) and we are
            // actually capturing non-silent audio. Otherwise muting silences the original
            // with nothing to replace it → total audio death (RJDSP guards the same way).
            if (!hasDumpPermission()) {
                // Self-heal: re-run the Shizuku `pm grant` so a missed/revoked DUMP doesn't
                // silently leave originals unmuted (duplication). Throttled to avoid spam.
                if (ShizukuPermissions.hasShizukuPermission()) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastDumpRegrant > 10_000) {
                        lastDumpRegrant = now
                        Log.w(TAG, "DUMP missing — re-granting via Shizuku")
                        ShizukuPermissions.applyAll(applicationContext)
                    }
                }
                Log.w(
                    TAG,
                    "mute SKIPPED: no DUMP permission (${sessions.size} sessions found). " +
                        "Grant via Shizuku or 'adb shell pm grant com.rp.seq android.permission.DUMP' " +
                        "or originals play unmuted (duplication)."
                )
                return
            }
            muteManager.setExcludedUids(setOf(Process.myUid()))
            muteManager.syncSessions(map)
            Log.i(TAG, "muted ${map.size} sessions")
        } catch (e: Exception) {
            Log.w(TAG, "session poll: ${e.message}")
        }
    }

    /** RJDSP buildAudioTrack: USAGE_UNKNOWN + ALLOW_CAPTURE_BY_NONE */
    private fun buildAudioTrack(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioTrack {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_UNKNOWN)
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
            .setFlags(0)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                }
            }
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSizeBytes)
            .build()
    }

    /**
     * RJDSP buildAudioRecord:
     * AudioPlaybackCapture matching MEDIA/GAME/UNKNOWN, exclude self UID.
     */
    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .excludeUid(Process.myUid())

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setAudioPlaybackCaptureConfig(configBuilder.build())
            .build()
    }

    private fun hasDumpPermission(): Boolean {
        return try {
            packageManager.checkPermission(
                android.Manifest.permission.DUMP,
                packageName
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun determineSampleRate(): Int {
        val s = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()?.takeIf { it > 0 }
        return s ?: 48000
    }

    private fun determineHalFrames(): Int {
        val f = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()?.takeIf { it > 0 }
        return f ?: 256
    }

    companion object {
        private const val TAG = "seq-eq-svc"
        private const val CHANNEL_ID = "seq_eq"
        private const val NOTIF_ID = 42
        private const val SESSION_POLL_MS = 1000L

        // Debug: when true, do NOT mute originals so we can verify capture RMS.
        @Volatile
        var debugNoMute = false

        const val ACTION_START = "com.rp.seq.eq.START"
        const val ACTION_STOP = "com.rp.seq.eq.STOP"
        const val ACTION_APPLY = "com.rp.seq.eq.APPLY"
        const val EXTRA_PROJECTION = "mediaProjectionData"
        private const val KEY_CONFIG = "config"
        private const val KEY_REVISION = "config_revision"

        // Engine + running state live ONLY in the :audio process (service). The plugin
        // (main process) must not touch these across processes.
        internal val sharedEngine = ParametricEq()
        internal val running = AtomicBoolean(false)

        /** Called from the plugin (main process) when the user changes the EQ. */
        fun persistConfigFor(context: Context, json: String) {
            val prefs = context.getSharedPreferences("seq_eq", Context.MODE_MULTI_PROCESS)
            val rev = prefs.getLong(KEY_REVISION, 0L) + 1
            // commit() (not apply()) so the revision is synchronously on disk before the
            // :audio process polls — cross-process visibility needs the write flushed.
            prefs.edit()
                .putString(KEY_CONFIG, json)
                .putLong(KEY_REVISION, rev)
                .commit()
        }

        /**
         * Cross-process running check via ActivityManager — reflects the real service state
         * in the :audio process (no stale-pref risk like the old "running" flag).
         */
        fun isRunning(context: Context): Boolean {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.getRunningServices(100).any {
                    it.service?.className?.endsWith(".EqAudioProcessorService") == true
                }
            } catch (e: Exception) {
                false
            }
        }

        /** Wake the :audio service to re-apply the persisted config live. */
        fun notifyApply(context: Context) {
            try {
                context.startService(
                    Intent(context, EqAudioProcessorService::class.java).apply {
                        action = ACTION_APPLY
                    }
                )
            } catch (_: Exception) {
            }
        }

        fun start(context: Context, projectionData: Intent) {
            val i = Intent(context, EqAudioProcessorService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROJECTION, projectionData)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, EqAudioProcessorService::class.java).apply {
                        action = ACTION_STOP
                    }
                )
            } catch (_: Exception) {
                context.stopService(Intent(context, EqAudioProcessorService::class.java))
            }
        }
    }
}
