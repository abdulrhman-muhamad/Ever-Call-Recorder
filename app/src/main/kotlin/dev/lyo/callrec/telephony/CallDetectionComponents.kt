// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.telephony

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.coolappstore.evercallrecorder.by.svhp.core.L
import com.coolappstore.evercallrecorder.by.svhp.settings.CallDetectionMode

/**
 * Keeps the two manifest-declared call-detection entry points enabled in
 * lockstep with the chosen [CallDetectionMode], so only one of them is ever
 * live:
 *
 *  - [CallStateReceiver] is a manifest BroadcastReceiver — free while idle,
 *    the OS only wakes it for a PHONE_STATE broadcast. Disabled when the
 *    InCallService path is selected so a call isn't reported twice.
 *  - [CallDetectionService] is bound by Telecom for as long as it is enabled
 *    and MANAGE_ONGOING_CALLS is granted — that binding is the "always in
 *    memory" cost, so it stays disabled (manifest default) unless selected.
 *
 * Component state persists across app updates; `App.onCreate` re-syncs on
 * every launch so a setting written before a crash still wins.
 *
 * Ported from hari161008 6c2f895 (`CallRecordingComponentGuard`), minus the
 * notification-listener component this app does not have.
 */
object CallDetectionComponents {

    fun sync(ctx: Context, mode: CallDetectionMode) {
        val app = ctx.applicationContext
        val useInCall = mode == CallDetectionMode.IN_CALL_SERVICE
        setEnabled(app, CallStateReceiver::class.java, !useInCall)
        setEnabled(app, CallDetectionService::class.java, useInCall)
        L.i(TAG, "synced mode=$mode → receiver=${!useInCall} inCallService=$useInCall")
    }

    private fun setEnabled(ctx: Context, clazz: Class<*>, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, clazz), state, PackageManager.DONT_KILL_APP,
            )
        }.onFailure { L.w(TAG, "setComponentEnabled ${clazz.simpleName}=$enabled failed: ${it.message}") }
    }

    private const val TAG = "CallDetectComp"
}
