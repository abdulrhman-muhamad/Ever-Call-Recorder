// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.permissions

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import com.coolappstore.evercallrecorder.by.svhp.aidl.IRecorderService
import com.coolappstore.evercallrecorder.by.svhp.core.L

/**
 * MANAGE_ONGOING_CALLS is what lets Telecom bind a non-UI InCallService that
 * is not the default dialer. It is a signature|role permission, so the app
 * can't request it — but it is backed by an app-op, and `appops set` from the
 * shell UID flips it. That is exactly what the daemon's `setAppOpAllow` (AIDL
 * op 31, added for MIUI) already does, so no new wire call is needed.
 *
 * Ported from hari161008 6c2f895, which used a separate shell AIDL for the
 * same `appops set` command.
 */
object CallDetectionPermission {
    private const val OP = "android:manage_ongoing_calls"
    private const val OP_NAME = "MANAGE_ONGOING_CALLS"

    fun isGranted(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        ops.unsafeCheckOpNoThrow(OP, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Returns true when the daemon reports `appops set … allow` exited 0. */
    fun grant(service: IRecorderService, packageName: String): Boolean = runCatching {
        val ok = service.setAppOpAllow(packageName, OP_NAME) == 1
        L.i(TAG, "grant $OP_NAME → ${if (ok) "ok" else "refused"}")
        ok
    }.onFailure { L.w(TAG, "grant $OP_NAME threw: ${it.message}") }.getOrDefault(false)

    private const val TAG = "CallDetectPerm"
}
