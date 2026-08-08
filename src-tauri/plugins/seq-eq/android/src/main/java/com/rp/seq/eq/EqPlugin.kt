package com.rp.seq.eq

import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResult
import app.tauri.PermissionState
import app.tauri.annotation.ActivityCallback
import app.tauri.annotation.Command
import app.tauri.annotation.Permission
import app.tauri.annotation.PermissionCallback
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import rikka.shizuku.Shizuku

@TauriPlugin(
    permissions = [
        Permission(
            strings = [Manifest.permission.RECORD_AUDIO],
            alias = "recordAudio"
        ),
        Permission(
            strings = [Manifest.permission.POST_NOTIFICATIONS],
            alias = "postNotification"
        ),
    ]
)
class EqPlugin(private val activity: Activity) : Plugin(activity) {

    private val shizukuResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val invoke = pendingStart
        pendingStart = null
        if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ShizukuPermissions.applyAll(activity)
            continueStart(invoke)
        } else {
            invoke?.reject("Shizuku permission denied — needed to grant DUMP/PROJECT_MEDIA")
        }
    }

    @Command
    fun startEngine(invoke: Invoke) {
        // Same-process: use the in-memory running flag (accurate). Do NOT use the persisted
        // "running" pref — it can be stale (e.g. left true after a process restart that
        // never started capture), which would make us skip starting the engine.
        if (EqAudioProcessorService.isRunning(activity)) {
            invoke.resolve()
            return
        }

        // Ensure privileged perms via Shizuku (DUMP, PROJECT_MEDIA, capture policy).
        // Without DUMP, session discovery fails and we mute originals but replay silence.
        if (ShizukuPermissions.isShizukuAvailable() && !ShizukuPermissions.hasShizukuPermission()) {
            pendingStart = invoke
            Shizuku.addRequestPermissionResultListener(shizukuResultListener)
            ShizukuPermissions.requestIfNeeded()
            return
        }
        if (ShizukuPermissions.hasShizukuPermission()) {
            ShizukuPermissions.applyAll(activity)
        }
        continueStart(invoke)
    }

    private fun continueStart(invoke: Invoke?) {
        if (invoke == null) return

        if (getPermissionState("recordAudio") != PermissionState.GRANTED) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissionForAliases(
                    arrayOf("recordAudio", "postNotification"),
                    invoke,
                    "onRecordPerm"
                )
            } else {
                requestPermissionForAlias("recordAudio", invoke, "onRecordPerm")
            }
            return
        }

        launchProjection(invoke)
    }

    @PermissionCallback
    private fun onRecordPerm(invoke: Invoke) {
        if (getPermissionState("recordAudio") == PermissionState.GRANTED) {
            launchProjection(invoke)
        } else {
            invoke.reject("RECORD_AUDIO permission denied")
        }
    }

    @ActivityCallback
    private fun onProjectionResult(invoke: Invoke, result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            EqAudioProcessorService.start(activity, result.data!!)
            invoke.resolve()
        } else {
            invoke.reject("MediaProjection permission denied")
        }
    }

    @Command
    fun stopEngine(invoke: Invoke) {
        EqAudioProcessorService.stop(activity)
        invoke.resolve()
    }

    @Command
    fun applyEq(invoke: Invoke) {
        try {
            // argsJson is the EqConfig object serialized by serde
            val raw = invoke.getRawArgs()
            val json = try {
                val obj = invoke.getArgs()
                when {
                    obj.has("eq") -> obj.get("eq").toString()
                    obj.has("preamp") || obj.has("bands") -> raw
                    else -> raw
                }
            } catch (_: Exception) {
                raw
            }
            Log.d(TAG, "applyEq: $json")
            // Persist + forward to the :audio service (cross-process). The service reads
            // config from SharedPreferences and applies it on next start/apply.
            EqAudioProcessorService.persistConfigFor(activity, json)
            EqAudioProcessorService.notifyApply(activity)
            invoke.resolve()
        } catch (e: Exception) {
            Log.e(TAG, "applyEq failed", e)
            invoke.reject(e.message)
        }
    }

    @Command
    fun engineStatus(invoke: Invoke) {
        val ret = JSObject()
        ret.put("running", EqAudioProcessorService.isRunning(activity))
        ret.put("platform", "android")
        invoke.resolve(ret)
    }

    private fun launchProjection(invoke: Invoke) {
        val mpm = activity.getSystemService(MediaProjectionManager::class.java)
        val intent = mpm.createScreenCaptureIntent()
        startActivityForResult(invoke, intent, "onProjectionResult")
    }

    private var pendingStart: Invoke? = null

    companion object {
        private const val TAG = "seq-eq-plugin"
    }
}
