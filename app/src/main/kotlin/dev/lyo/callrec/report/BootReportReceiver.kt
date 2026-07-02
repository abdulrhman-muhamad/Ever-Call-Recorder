// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.report

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the report sync after a device reboot. Without a boot receiver the app
 * process never starts on boot, so reports queued before the restart would sit
 * until the user next opens the app or makes a call. Receiving BOOT_COMPLETED
 * cold-starts the process — App.onCreate already calls [ReportSync.start]; the
 * explicit call here is an idempotent belt-and-suspenders and the real work
 * happens on the recorder's appScope.
 */
class BootReportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> runCatching { ReportSync.start(context.applicationContext) }
        }
    }
}
