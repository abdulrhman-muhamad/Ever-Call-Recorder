// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.permissions

import android.content.Context
import android.provider.Settings
import com.coolappstore.evercallrecorder.by.svhp.accessibility.AccessibilityHelper
import com.coolappstore.evercallrecorder.by.svhp.di.AppContainer
import com.coolappstore.evercallrecorder.by.svhp.recorder.DaemonHealth
import com.coolappstore.evercallrecorder.by.svhp.settings.RecordingMode

data class SetupStatus(
    val mode: RecordingMode,
    // Shizuku-mode fields
    val shizukuReady: Boolean,
    // Accessibility-mode fields
    val accessibilityEnabled: Boolean,
    // Common fields
    val runtimePermsGranted: Boolean,
    val overlayGranted: Boolean,
    val batteryExempt: Boolean,
    /**
     * Xiaomi/MIUI proprietary permissions confirmed. Always `true` on non-MIUI
     * devices (the onboarding step is hidden there). On MIUI it tracks the
     * persisted acknowledgment — see [com.coolappstore.evercallrecorder.by.svhp.permissions.MiuiPermissions].
     */
    val miuiReady: Boolean,
) {
    val allReady: Boolean get() = when (mode) {
        RecordingMode.SHIZUKU -> shizukuReady && runtimePermsGranted && overlayGranted && batteryExempt && miuiReady
        RecordingMode.ACCESSIBILITY -> accessibilityEnabled && runtimePermsGranted && overlayGranted && batteryExempt && miuiReady
    }

    companion object {
        fun probe(ctx: Context, container: AppContainer): SetupStatus {
            val mode = container.recordingMode.value
            if (mode == RecordingMode.SHIZUKU) container.shizuku.refresh()
            return SetupStatus(
                mode = mode,
                shizukuReady = container.shizuku.health.value is DaemonHealth.Bound,
                accessibilityEnabled = AccessibilityHelper.isEnabled(ctx),
                runtimePermsGranted = AppPermissions.allGranted(ctx),
                overlayGranted = Settings.canDrawOverlays(ctx),
                batteryExempt = BatteryOptimizations.isIgnoring(ctx),
                miuiReady = !MiuiPermissions.isMiui() || container.miuiAcknowledged.value,
            )
        }
    }
}
