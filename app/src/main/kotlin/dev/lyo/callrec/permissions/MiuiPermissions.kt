// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.permissions

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.coolappstore.evercallrecorder.by.svhp.aidl.IRecorderService

/**
 * Xiaomi/MIUI ship a proprietary permission layer on top of AOSP. The toggles
 * the recorder needs — Autostart, Show-on-lock-screen, "Display pop-up windows
 * while running in the background", and home-screen shortcut creation — are
 * NOT part of the public Android permission model:
 *
 *   - There's no `pm grant` / runtime-dialog for them.
 *   - There's no `checkSelfPermission` to read their state.
 *
 * They live in MIUI's own AppOps table under numeric op codes. With the
 * Shizuku UserService (shell UID) we can flip them with `appops set`, exactly
 * like `adb shell appops set …` would — and crucially this works with MIUI
 * optimization left ON, so we never ask the user to disable it.
 *
 * Because reading numeric MIUI ops back (`appops get 10008`) is unreliable
 * across ROM builds, the auto-grant is *best-effort*: the onboarding flow pairs
 * it with deep-links to MIUI's own screens and a manual confirmation.
 */
object MiuiPermissions {

    private const val TAG = "Callrec"

    // MIUI proprietary AppOps op codes. Stable across MIUI 10–14.
    const val OP_AUTO_START = "10008"               // Autostart / background autostart
    const val OP_INSTALL_SHORTCUT = "10017"         // Create home-screen shortcut
    const val OP_SHOW_WHEN_LOCKED = "10020"         // Show on lock screen
    const val OP_BACKGROUND_START_ACTIVITY = "10021" // "Display pop-up windows while in background"

    // Autostart reads from op 10008 ONLY. Empirically on HyperOS (V816) op 10008
    // is the one that actually tracks the user's Autostart toggle — it flips
    // allow→ignore when the permission is revoked. Op 10007 looked like a HyperOS
    // alias but is a permanently-"allow" decoy: it never changes, so OR-ing it in
    // made Autostart always read granted and broke revoke-detection. Keep this a
    // single-element list so the multi-candidate read/write plumbing still works
    // if a future ROM genuinely needs a second code.
    val autoStartOpCandidates: List<String> = listOf(OP_AUTO_START)

    // Named AOSP op behind MIUI's "Display pop-up windows" toggle.
    const val OP_SYSTEM_ALERT_WINDOW = "SYSTEM_ALERT_WINDOW"

    /** Every op we try to flip through Shizuku, in display order. */
    val grantableOps: List<String> = listOf(
        OP_AUTO_START,
        OP_BACKGROUND_START_ACTIVITY,
        OP_SHOW_WHEN_LOCKED,
        OP_INSTALL_SHORTCUT,
        OP_SYSTEM_ALERT_WINDOW,
    )

    /** Ops shown (and required) on the onboarding checklist, in display order. */
    val checklistOps: List<String> = listOf(
        OP_AUTO_START,
        OP_BACKGROUND_START_ACTIVITY,
        OP_SYSTEM_ALERT_WINDOW,
        OP_SHOW_WHEN_LOCKED,
        OP_INSTALL_SHORTCUT,
    )

    /** Human-readable label per op for the checklist UI. */
    val opLabels: Map<String, String> = mapOf(
        OP_AUTO_START to "Autostart",
        OP_BACKGROUND_START_ACTIVITY to "Open new windows while running in the background",
        OP_SYSTEM_ALERT_WINDOW to "Display pop-up windows",
        OP_SHOW_WHEN_LOCKED to "Show on lock screen",
        OP_INSTALL_SHORTCUT to "Home screen shortcuts",
    )

    fun isMiui(): Boolean {
        val brand = Build.BRAND?.lowercase().orEmpty()
        val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
        if (manufacturer == "xiaomi" || brand in setOf("xiaomi", "redmi", "poco")) return true
        // HyperOS / MIUI expose a version prop even when BRAND is rebadged.
        return !systemProp("ro.miui.ui.version.name").isNullOrBlank() ||
            !systemProp("ro.mi.os.version.name").isNullOrBlank()
    }

    /**
     * Best-effort grant of every MIUI op via the privileged service. Returns
     * the per-op verification result keyed by op string. A `true` means we
     * either read back "allow" or the `appops set` exited cleanly; `false`
     * means the set failed outright. Callers must NOT treat a partial map as
     * fatal — the manual deep-link path is the safety net.
     *
     * MUST be called off the main thread (Binder transactions).
     */
    fun autoGrant(service: IRecorderService, packageName: String): Map<String, Boolean> {
        val results = LinkedHashMap<String, Boolean>()
        for (op in grantableOps) {
            // Autostart lives under a different op code per ROM (10008 classic,
            // 10007 HyperOS) — try to set both so we hit whichever this ROM uses.
            val toSet = if (op == OP_AUTO_START) autoStartOpCandidates else listOf(op)
            toSet.forEach { candidate ->
                runCatching { service.setAppOpAllow(packageName, candidate) }
                    .onFailure { Log.w(TAG, "autoGrant set $candidate threw", it) }
            }
            // Trust the READ-BACK, not the set's exit code. On MIUI/HyperOS some
            // ops (notably Autostart) accept `appops set … allow` with exit 0 yet
            // never persist — counting exit-0 as success made auto-grant falsely
            // claim Autostart was granted.
            results[op] = isOpAllowed(service, packageName, op)
        }
        Log.i(TAG, "MIUI autoGrant results: $results")
        return results
    }

    /**
     * True if [op] reads "allow". Autostart is special: its op code varies by
     * ROM (10008 ↔ 10007), so it's granted when EITHER candidate reads allow.
     */
    private fun isOpAllowed(service: IRecorderService, packageName: String, op: String): Boolean {
        val candidates = if (op == OP_AUTO_START) autoStartOpCandidates else listOf(op)
        return candidates.any { candidate ->
            runCatching { service.getAppOpMode(packageName, candidate) }
                .getOrNull()
                ?.contains("allow", ignoreCase = true) == true
        }
    }

    /**
     * Read each op's granted state for [packageName] via the privileged service.
     * Returns op → true when `appops get` reports "allow". MIUI ops echo as
     * `MIUIOP(10021): allow|ignore`, so a simple "allow" substring match is
     * reliable. MUST be called off the main thread (Binder transactions).
     */
    fun readOps(
        service: IRecorderService,
        packageName: String,
        ops: List<String> = checklistOps,
    ): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        for (op in ops) {
            // isOpAllowed handles Autostart's ROM-dependent op code (10008/10007).
            out[op] = isOpAllowed(service, packageName, op)
        }
        return out
    }

    /**
     * Open MIUI's per-app "Other permissions" editor (Show-on-lock-screen,
     * background pop-up, shortcut, etc). Falls back through known activity
     * names and finally the generic app-details page.
     */
    fun openPermissionEditor(ctx: Context) {
        val pkg = ctx.packageName
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity",
                )
                .putExtra("extra_pkgname", pkg),
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
                )
                .putExtra("extra_pkgname", pkg),
        )
        if (!startFirstResolvable(ctx, candidates)) openAppDetails(ctx)
    }

    /** Open MIUI's Autostart manager so the user can flip Autostart on. */
    fun openAutoStart(ctx: Context) {
        val candidates = listOf(
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            Intent("miui.intent.action.OP_AUTO_START")
                .addCategory(Intent.CATEGORY_DEFAULT),
        )
        if (!startFirstResolvable(ctx, candidates)) openAppDetails(ctx)
    }

    private fun openAppDetails(ctx: Context) {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    private fun startFirstResolvable(ctx: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(ctx.packageManager) != null) {
                if (runCatching { ctx.startActivity(intent) }.isSuccess) return true
            }
        }
        return false
    }

    private fun systemProp(key: String): String? = runCatching {
        @SuppressLint("PrivateApi")
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
