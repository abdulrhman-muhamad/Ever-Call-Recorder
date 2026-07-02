// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.ui.onboarding

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.coolappstore.evercallrecorder.by.svhp.R
import com.coolappstore.evercallrecorder.by.svhp.di.AppContainer
import com.coolappstore.evercallrecorder.by.svhp.permissions.AppPermissions
import com.coolappstore.evercallrecorder.by.svhp.permissions.BatteryOptimizations
import com.coolappstore.evercallrecorder.by.svhp.permissions.MiuiPermissions
import com.coolappstore.evercallrecorder.by.svhp.permissions.rememberPermissionsState
import com.coolappstore.evercallrecorder.by.svhp.recorder.DaemonHealth
import com.coolappstore.evercallrecorder.by.svhp.settings.RecordingMode
import com.coolappstore.evercallrecorder.by.svhp.accessibility.AccessibilityHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHIZUKU_FORK_URL = "https://github.com/thedjchi/Shizuku"

@Composable
fun OnboardingScreen(
    container: AppContainer,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    // Selected mode — persisted via settings, local state for immediate UI update
    val savedMode by container.settings.recordingMode.collectAsState(initial = RecordingMode.SHIZUKU)
    var selectedMode by remember(savedMode) { mutableStateOf(savedMode) }

    val shizukuState by container.shizuku.health.collectAsState()
    val (perms, requestPerms) = rememberPermissionsState()
    val grantedSet by perms.granted.collectAsState()
    val allRuntimeGranted = grantedSet.containsAll(AppPermissions.essential)

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var batteryExempt by remember { mutableStateOf(BatteryOptimizations.isIgnoring(ctx)) }
    var accessibilityEnabled by remember { mutableStateOf(AccessibilityHelper.isEnabled(ctx)) }

    // Per-op granted state for the MIUI background/pop-up checklist, read back
    // live via Shizuku (MIUI ops echo as `MIUIOP(10021): allow|ignore`). Only
    // relevant on Xiaomi/MIUI — non-MIUI devices report ready unconditionally.
    var popupOpStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val popupReady = !MiuiPermissions.isMiui() ||
        MiuiPermissions.checklistOps.all { popupOpStates[it] == true }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.currentStateFlow.collect {
            if (it.isAtLeast(Lifecycle.State.RESUMED)) {
                overlayGranted = Settings.canDrawOverlays(ctx)
                batteryExempt = BatteryOptimizations.isIgnoring(ctx)
                accessibilityEnabled = AccessibilityHelper.isEnabled(ctx)
                container.shizuku.service.value?.let { svc ->
                    val states = withContext(Dispatchers.IO) {
                        MiuiPermissions.readOps(svc, ctx.packageName)
                    }
                    popupOpStates = states
                    container.settings.setMiuiPermsAcknowledged(
                        states.isNotEmpty() && states.values.all { v -> v }
                    )
                }
            }
        }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* recomputed via observer */ }

    val shizukuInstalled = shizukuState !is DaemonHealth.NotInstalled
    val shizukuActivated = shizukuState !is DaemonHealth.NotInstalled && shizukuState !is DaemonHealth.NotRunning
    val shizukuPermitted = shizukuState is DaemonHealth.Bound

    var miuiGrantRunning by remember { mutableStateOf(false) }

    val commonReady = allRuntimeGranted && overlayGranted && batteryExempt
    val readyToContinue = when (selectedMode) {
        // MIUI pop-up/background ops are granted + verified via Shizuku, so the
        // popup gate only applies in Shizuku mode; popupReady is true anyway on
        // non-MIUI devices.
        RecordingMode.SHIZUKU ->
            commonReady && shizukuInstalled && shizukuActivated && shizukuPermitted && popupReady
        RecordingMode.ACCESSIBILITY -> commonReady && accessibilityEnabled
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        var visible by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { visible = true }

        @Composable
        fun Fade(delayMs: Int, content: @Composable () -> Unit) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(500, delayMs, EaseOutCubic)) +
                    slideInVertically(tween(540, delayMs, EaseOutCubic)) { (it * 0.20f).toInt() },
            ) { content() }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Fade(0) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.onboarding_title),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        stringResource(R.string.app_tagline),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Mode chooser ─────────────────────────────────────────────
            Fade(80) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.onboarding_choose_mode_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.onboarding_choose_mode_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    ModeCard(
                        title = stringResource(R.string.onboarding_mode_shizuku_title),
                        desc = stringResource(R.string.onboarding_mode_shizuku_desc),
                        icon = Icons.Outlined.Verified,
                        selected = selectedMode == RecordingMode.SHIZUKU,
                        onSelect = {
                            selectedMode = RecordingMode.SHIZUKU
                            scope.launch { container.settings.setRecordingMode(RecordingMode.SHIZUKU) }
                        },
                    )

                    ModeCard(
                        title = stringResource(R.string.onboarding_mode_accessibility_title),
                        desc = stringResource(R.string.onboarding_mode_accessibility_desc),
                        icon = Icons.Outlined.Settings,
                        selected = selectedMode == RecordingMode.ACCESSIBILITY,
                        onSelect = {
                            selectedMode = RecordingMode.ACCESSIBILITY
                            scope.launch { container.settings.setRecordingMode(RecordingMode.ACCESSIBILITY) }
                        },
                    )
                }
            }

            Fade(160) { Divider() }

            // ── Mode-specific steps ───────────────────────────────────────
            Fade(240) {
                if (selectedMode == RecordingMode.SHIZUKU) {
                    ShizukuSteps(
                        container = container,
                        shizukuInstalled = shizukuInstalled,
                        shizukuActivated = shizukuActivated,
                        shizukuPermitted = shizukuPermitted,
                        shizukuState = shizukuState,
                        ctx = ctx,
                        scope = scope,
                        uriHandler = uriHandler,
                    )
                } else {
                    StepCard(
                        index = 1,
                        icon = Icons.Outlined.Settings,
                        title = stringResource(R.string.onboarding_step_accessibility),
                        desc = stringResource(R.string.onboarding_step_accessibility_desc),
                        done = accessibilityEnabled,
                        action = if (!accessibilityEnabled) {
                            {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { ctx.startActivity(intent) }
                            }
                        } else null,
                        actionLabel = stringResource(R.string.onboarding_step_accessibility_action),
                    )
                }
            }

            // ── Common steps ──────────────────────────────────────────────
            Fade(320) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val showsNotifStep = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    var stepIdx = if (selectedMode == RecordingMode.SHIZUKU) 4 else 2
                    if (showsNotifStep) {
                        val notifGranted = ContextCompat.checkSelfPermission(
                            ctx, android.Manifest.permission.POST_NOTIFICATIONS,
                        ) == PackageManager.PERMISSION_GRANTED
                        StepCard(
                            index = stepIdx++,
                            icon = Icons.Outlined.Bolt,
                            title = stringResource(R.string.onboarding_notif_perm_title),
                            desc = stringResource(R.string.onboarding_notif_perm_body),
                            done = notifGranted,
                            action = if (!notifGranted) {
                                { notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                            } else null,
                            actionLabel = stringResource(R.string.onboarding_notif_perm_title),
                        )
                    }
                    StepCard(
                        index = stepIdx++,
                        icon = Icons.Outlined.LockOpen,
                        title = "System permissions",
                        desc = "Microphone, phone state, call log, contacts — needed to start recording and identify who you called.",
                        done = allRuntimeGranted,
                        action = if (!allRuntimeGranted) requestPerms else null,
                        actionLabel = "Allow",
                    )
                    StepCard(
                        index = stepIdx++,
                        icon = Icons.Outlined.Layers,
                        title = stringResource(R.string.onboarding_step_overlay),
                        desc = stringResource(R.string.onboarding_step_overlay_desc),
                        done = overlayGranted,
                        action = if (!overlayGranted) {
                            {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    "package:${ctx.packageName}".toUri(),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { ctx.startActivity(intent) }
                            }
                        } else null,
                        actionLabel = stringResource(R.string.onboarding_step_overlay_action),
                    )
                    StepCard(
                        index = stepIdx++,
                        icon = Icons.Outlined.BatteryFull,
                        title = stringResource(R.string.onboarding_step_battery),
                        desc = stringResource(R.string.onboarding_step_battery_desc),
                        done = batteryExempt,
                        action = if (!batteryExempt) {
                            { BatteryOptimizations.requestExemption(ctx) }
                        } else null,
                        actionLabel = stringResource(R.string.onboarding_step_battery_action),
                    )

                    // Xiaomi/MIUI proprietary background & pop-up permissions.
                    // Auto-grant + live verification run through Shizuku, so this
                    // only gates "continue" in Shizuku mode (see readyToContinue).
                    if (MiuiPermissions.isMiui()) {
                        PopupPermissionsCard(
                            index = stepIdx++,
                            opStates = popupOpStates,
                            autoGrantEnabled = shizukuPermitted && !miuiGrantRunning,
                            autoGrantRunning = miuiGrantRunning,
                            onAutoGrant = {
                                val svc = container.shizuku.service.value ?: return@PopupPermissionsCard
                                miuiGrantRunning = true
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        MiuiPermissions.autoGrant(svc, ctx.packageName)
                                    }
                                    val states = withContext(Dispatchers.IO) {
                                        MiuiPermissions.readOps(svc, ctx.packageName)
                                    }
                                    popupOpStates = states
                                    container.settings.setMiuiPermsAcknowledged(
                                        states.isNotEmpty() && states.values.all { v -> v }
                                    )
                                    miuiGrantRunning = false
                                }
                            },
                            onOpenPermissions = { MiuiPermissions.openPermissionEditor(ctx) },
                            onOpenAutostart = { MiuiPermissions.openAutoStart(ctx) },
                        )
                    }
                }
            }

            Fade(400) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (selectedMode == RecordingMode.SHIZUKU) {
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.onboarding_shizuku_tip_title),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.onboarding_shizuku_tip_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = { uriHandler.openUri(SHIZUKU_FORK_URL) }) {
                                    Text(stringResource(R.string.onboarding_shizuku_tip_cta))
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            container.shizuku.refresh()
                            overlayGranted = Settings.canDrawOverlays(ctx)
                            batteryExempt = BatteryOptimizations.isIgnoring(ctx)
                            accessibilityEnabled = AccessibilityHelper.isEnabled(ctx)
                            scope.launch {
                                container.shizuku.service.value?.let { svc ->
                                    val states = withContext(Dispatchers.IO) {
                                        MiuiPermissions.readOps(svc, ctx.packageName)
                                    }
                                    popupOpStates = states
                                    container.settings.setMiuiPermsAcknowledged(
                                        states.isNotEmpty() && states.values.all { v -> v }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Check status")
                    }

                    AnimatedVisibility(
                        visible = selectedMode == RecordingMode.SHIZUKU && shizukuState is DaemonHealth.NoPermission,
                        enter = fadeIn(tween(380)),
                        exit = fadeOut(tween(280)),
                    ) {
                        Text(
                            text = stringResource(R.string.err_shizuku_denied) +
                                " — open Shizuku and grant permission manually.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                container.settings.setOnboardingDone(true)
                                onDone()
                            }
                        },
                        enabled = readyToContinue,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.onboarding_continue)) }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(380),
        label = "mode-card-color",
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (selected) {
                FilledTonalButton(onClick = {}) { Text(stringResource(R.string.onboarding_mode_selected)) }
            } else {
                OutlinedButton(onClick = onSelect) { Text(stringResource(R.string.onboarding_mode_select)) }
            }
        }
    }
}

@Composable
private fun ShizukuSteps(
    container: AppContainer,
    shizukuInstalled: Boolean,
    shizukuActivated: Boolean,
    shizukuPermitted: Boolean,
    shizukuState: DaemonHealth,
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    uriHandler: androidx.compose.ui.platform.UriHandler,
) {
    StepCard(
        index = 1,
        icon = Icons.Outlined.Download,
        title = stringResource(R.string.onboarding_step_install),
        desc = stringResource(R.string.onboarding_step_install_desc),
        done = shizukuInstalled,
        action = if (!shizukuInstalled) { { openShizukuStorePage(ctx) } } else null,
        actionLabel = stringResource(R.string.onboarding_open_shizuku),
    )
    StepCard(
        index = 2,
        icon = Icons.Outlined.Bolt,
        title = stringResource(R.string.onboarding_step_activate),
        desc = stringResource(R.string.onboarding_step_activate_desc),
        done = shizukuActivated,
        action = if (shizukuInstalled && !shizukuActivated) { { openShizukuApp(ctx) } } else null,
        actionLabel = stringResource(R.string.onboarding_open_shizuku),
    )
    StepCard(
        index = 3,
        icon = Icons.Outlined.Verified,
        title = stringResource(R.string.onboarding_step_grant),
        desc = stringResource(R.string.onboarding_step_grant_desc),
        done = shizukuPermitted,
        action = if (shizukuActivated && !shizukuPermitted) {
            { scope.launch { container.shizuku.requestPermission() } }
        } else null,
        actionLabel = stringResource(R.string.onboarding_step_grant),
    )
}

@Composable
private fun StepCard(
    index: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    done: Boolean,
    action: (() -> Unit)?,
    actionLabel: String,
) {
    val cardColor by animateColorAsState(
        targetValue = if (done) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(420),
        label = "step-card-color",
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (done) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                        )
                    } else {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "$index. $title",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (action != null) {
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = action) { Text(actionLabel) }
                }
            }
        }
    }
}

/**
 * Background / pop-up permissions card, shown on all devices. Each op is
 * verified live via Shizuku (`appops get` → `MIUIOP(n): allow|ignore`) and
 * rendered as its own checked/unchecked row. The step is "done" only when ALL
 * ops read back as allowed — there is no manual-confirm fallback.
 *
 *   - Auto-grant — flips the ops via the Shizuku shell service.
 *   - Deep-links — open the system permission editor / autostart manager so the
 *     user can flip anything the op-codes missed on their ROM.
 */
@Composable
private fun PopupPermissionsCard(
    index: Int,
    opStates: Map<String, Boolean>,
    autoGrantEnabled: Boolean,
    autoGrantRunning: Boolean,
    onAutoGrant: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAutostart: () -> Unit,
) {
    val allGranted = MiuiPermissions.checklistOps.all { opStates[it] == true }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (allGranted) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        if (allGranted) Icons.Outlined.CheckCircle else Icons.Outlined.Layers,
                        contentDescription = null,
                        tint = if (allGranted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(if (allGranted) 36.dp else 28.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "$index. Background & pop-up permissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Required so call recording can start in the background. Each is verified live:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Per-permission live status rows.
            MiuiPermissions.checklistOps.forEach { op ->
                val granted = opStates[op] == true
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        MiuiPermissions.opLabels[op] ?: op,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            FilledTonalButton(
                onClick = onAutoGrant,
                enabled = autoGrantEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (autoGrantRunning) "Granting…" else "Auto-grant via Shizuku")
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onOpenPermissions, modifier = Modifier.weight(1f)) {
                    Text("Permissions")
                }
                OutlinedButton(onClick = onOpenAutostart, modifier = Modifier.weight(1f)) {
                    Text("Autostart")
                }
            }
        }
    }
}

private fun openShizukuStorePage(ctx: android.content.Context) {
    val web = Intent(Intent.ACTION_VIEW, "https://github.com/thedjchi/Shizuku/releases".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(web) }
}

private fun openShizukuApp(ctx: android.content.Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(launch) }
    } else {
        openShizukuStorePage(ctx)
    }
}
