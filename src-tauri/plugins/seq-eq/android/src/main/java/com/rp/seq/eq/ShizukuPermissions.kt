package com.rp.seq.eq

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.io.OutputStream

/**
 * Runtime privileged permission bootstrap, mirroring RootlessJamesDSP's
 * ShizukuSystemServerApi. Without these (DUMP + PROJECT_MEDIA + allowed capture
 * policy) the AudioPlaybackCapture returns silence while we mute originals —
 * net effect: total audio death.
 */
object ShizukuPermissions {
    private const val TAG = "seq-eq-shizuku"

    const val REQUEST_CODE = 0x5351 // "SQ"

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && !Shizuku.isPreV11()
        } catch (e: Throwable) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /** Request the user to grant Shizuku access. Returns true if already granted. */
    fun requestIfNeeded(): Boolean {
        if (hasShizukuPermission()) return true
        if (!isShizukuAvailable()) return false
        try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Throwable) {
            Log.e(TAG, "requestPermission", e)
        }
        return false
    }

    fun grantRuntimePermission(pkg: String, permission: String, userId: Int = Process.myUserHandle().hashCode()) {
        // Prefer the shell `pm grant` path — it's exactly what `adb shell pm grant` runs and
        // is proven to work on device. Only fall back to the binder IPermissionManager path.
        if (exec("pm grant $pkg $permission")) {
            Log.i(TAG, "pm grant $pkg $permission OK")
            return
        }
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val raw = getService.invoke(null, "permissionmgr") as? android.os.IBinder
                ?: throw IllegalStateException("permissionmgr service unavailable")
            val wrapped = ShizukuBinderWrapper(raw)
            val clazz = Class.forName("android.permission.IPermissionManager")
            val stub = clazz.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, wrapped)
            val grant = clazz.getMethod("grantRuntimePermission", String::class.java, String::class.java, Int::class.java)
            grant.invoke(stub, pkg, permission, userId)
            Log.i(TAG, "binder grant $pkg $permission OK")
        } catch (e: Throwable) {
            Log.w(TAG, "grantRuntimePermission failed for $permission", e)
        }
    }

    fun setProjectMediaOp(pkg: String, uid: Int = Process.myUid(), allow: Boolean = true) {
        try {
            val opMethod = Class.forName("android.app.AppOpsManager")
                .getMethod("strOpToOp", String::class.java)
            val op = opMethod.invoke(null, "PROJECT_MEDIA") as? Int ?: run {
                Log.w(TAG, "strOpToOp PROJECT_MEDIA returned null")
                return
            }
            val modeToName = Class.forName("android.app.AppOpsManager")
                .getMethod("modeToName", Int::class.java)
            var mode = -1
            val wantedMode = if (allow) "allow" else "deny"
            for (i in 0..10) {
                val name = modeToName.invoke(null, i) as? String ?: continue
                if (wantedMode == name) {
                    mode = i
                    break
                }
            }
            if (mode < 0) return

            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val raw = getService.invoke(null, "appops") as? android.os.IBinder ?: return
            val wrapped = ShizukuBinderWrapper(raw)
            val clazz = Class.forName("com.android.internal.app.IAppOpsService")
            val stub = clazz.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, wrapped)
            val setMode = clazz.getMethod("setMode", Int::class.java, Int::class.java, String::class.java, Int::class.java)
            setMode.invoke(stub, op, uid, pkg, mode)
        } catch (e: Throwable) {
            Log.w(TAG, "setProjectMediaOp failed", e)
        }
    }

    /** Force-allow media projection capture for a uid (unlocks restricted apps). */
    fun setAllowedCapturePolicy(uid: Int, allowByMediaProjection: Boolean) {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val raw = getService.invoke(null, "media.audio_policy") as? android.os.IBinder ?: return
            val wrapped = ShizukuBinderWrapper(raw)
            val clazz = Class.forName("android.media.IAudioPolicyService")
            val stub = clazz.getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, wrapped)
            val set = clazz.getMethod("setAllowedCapturePolicy", Int::class.java, Int::class.java)
            val flags = if (allowByMediaProjection) 0 else (0x10 or 0x20)
            set.invoke(stub, uid, flags)
            Log.i(TAG, "setAllowedCapturePolicy uid=$uid flags=$flags")
        } catch (e: Throwable) {
            Log.w(TAG, "setAllowedCapturePolicy failed uid=$uid", e)
        }
    }

    private fun exec(cmd: String): Boolean {
        return try {
            val newProcess = Shizuku::class.java
                .getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            newProcess.isAccessible = true
            val proc = newProcess.invoke(null, arrayOf("sh"), null, null) ?: return false
            val os = proc.javaClass.getMethod("getOutputStream").invoke(proc) as? OutputStream ?: return false
            os.write((cmd + "\nexit\n").toByteArray())
            os.flush()
            os.close()
            val exit = (proc.javaClass.getMethod("waitFor").invoke(proc) as? Number)?.toInt() ?: -1
            exit == 0
        } catch (e: Throwable) {
            Log.w(TAG, "exec failed", e)
            false
        }
    }

    /** Grant everything needed: DUMP, RECORD_AUDIO, PROJECT_MEDIA op. */
    fun applyAll(context: Context): Boolean {
        val pkg = context.packageName
        try {
            grantRuntimePermission(pkg, Manifest.permission.DUMP)
            grantRuntimePermission(pkg, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) {
                grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)
            }
            setProjectMediaOp(pkg, Process.myUid(), allow = true)
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "applyAll", e)
            return false
        }
    }
}
