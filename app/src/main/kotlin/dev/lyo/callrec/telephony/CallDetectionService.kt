// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.telephony

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.coolappstore.evercallrecorder.by.svhp.App
import com.coolappstore.evercallrecorder.by.svhp.core.L
import com.coolappstore.evercallrecorder.by.svhp.settings.CallDetectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Optional call-detection path through Telecom instead of the PHONE_STATE
 * broadcast. Registered as a **non-UI, secondary** InCallService (see the
 * manifest meta-data): it does not make this app a dialer and does not touch
 * whatever the default dialer shows. Telecom only binds it once the
 * MANAGE_ONGOING_CALLS app-op is granted (Settings → Call detection).
 *
 * Inert unless [CallDetectionMode.IN_CALL_SERVICE] is selected — every
 * callback re-checks the setting, so the mere presence of this service in
 * the manifest changes nothing on the default path.
 *
 * It drives [CallMonitorService] with the same intent contract as
 * [CallStateReceiver] (ACTION_CALL_START on the first ACTIVE call, ACTION_CALL_END
 * when the last tracked call disconnects). Self-managed calls (WhatsApp,
 * Telegram, …) are deliberately ignored: their audio never touches the modem
 * path, so the VOICE_CALL/UPLINK/DOWNLINK ladder would record silence and
 * poison the per-device capability cache for the next real call.
 *
 * Ported from hari161008 6c2f895 (`AppInCallService`), adapted to this app's
 * DI, AutoRecordDecider and FGS start path.
 */
class CallDetectionService : InCallService() {

    private val callbacks = mutableMapOf<Call, Call.Callback>()
    /** Calls we consider "in progress"; touched on the main thread only. */
    private val tracked = mutableSetOf<Call>()
    private var recording = false

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (call.details?.hasProperty(Call.Details.PROPERTY_SELF_MANAGED) == true) {
            L.i(TAG, "onCallAdded: self-managed (VoIP) call ignored")
            return
        }
        val cb = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = onState(call, state)
        }
        call.registerCallback(cb)
        callbacks[call] = cb
        onState(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        onState(call, Call.STATE_DISCONNECTED)
    }

    private fun onState(call: Call, state: Int) {
        val container = (application as App).container
        container.appScope.launch {
            if (container.settings.callDetectionMode.first() != CallDetectionMode.IN_CALL_SERVICE) return@launch
            when (state) {
                Call.STATE_ACTIVE -> {
                    val number = call.details?.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
                    val incoming = call.details?.callDirection == Call.Details.DIRECTION_INCOMING
                    val auto = runCatching { AutoRecordDecider.shouldAutoRecord(applicationContext, number, callSimId = null) }
                        .getOrDefault(true)
                    withContext(Dispatchers.Main.immediate) {
                        tracked += call
                        if (recording) return@withContext
                        if (!auto) {
                            L.d(TAG, "auto-record skipped by filter")
                            return@withContext
                        }
                        recording = true
                        // Bound by Telecom we already have foreground importance,
                        // but keep the same overlay promotion the receiver uses so
                        // both paths behave identically on OEMs that are picky.
                        if (OverlayTrick.canShow(applicationContext)) OverlayTrick.briefly(applicationContext)
                        val svc = Intent(applicationContext, CallMonitorService::class.java).apply {
                            action = CallMonitorService.ACTION_CALL_START
                            putExtra(CallMonitorService.EXTRA_PHONE_STATE, TelephonyManager.EXTRA_STATE_OFFHOOK)
                            putExtra(CallMonitorService.EXTRA_INCOMING, incoming)
                            if (number != null) putExtra(CallMonitorService.EXTRA_NUMBER, number)
                        }
                        L.i(TAG, "ACTIVE → CALL_START (incoming=$incoming${if (number.isNullOrBlank()) "" else ", number present"})")
                        runCatching { ContextCompat.startForegroundService(applicationContext, svc) }
                            .onFailure { L.e(TAG, "startForegroundService failed: ${it.message}"); recording = false }
                    }
                }
                Call.STATE_DISCONNECTED -> withContext(Dispatchers.Main.immediate) {
                    tracked -= call
                    if (tracked.isEmpty() && recording) {
                        recording = false
                        L.i(TAG, "last call DISCONNECTED → CALL_END")
                        val svc = Intent(applicationContext, CallMonitorService::class.java)
                            .setAction(CallMonitorService.ACTION_CALL_END)
                        runCatching { startService(svc) }
                            .onFailure { L.d(TAG, "startService(end) noop: ${it.message}") }
                    }
                }
                else -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "CallDetect"
    }
}
