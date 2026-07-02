// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.ui.settings

import android.app.DownloadManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.coolappstore.evercallrecorder.by.svhp.BuildConfig
import com.coolappstore.evercallrecorder.by.svhp.R
import com.coolappstore.evercallrecorder.by.svhp.di.AppContainer
import com.coolappstore.evercallrecorder.by.svhp.recorder.Capabilities
import com.coolappstore.evercallrecorder.by.svhp.report.CallReporter
import com.coolappstore.evercallrecorder.by.svhp.settings.AutoRecordScope
import com.coolappstore.evercallrecorder.by.svhp.recorder.Strategy
import com.coolappstore.evercallrecorder.by.svhp.settings.RecordingFormat
import com.coolappstore.evercallrecorder.by.svhp.settings.RecordingMode
import com.coolappstore.evercallrecorder.by.svhp.ui.components.strategyQuality
import com.coolappstore.evercallrecorder.by.svhp.ui.legal.LegalDisclaimerSheet
import com.coolappstore.evercallrecorder.by.svhp.util.enqueueApkDownload
import com.coolappstore.evercallrecorder.by.svhp.util.fetchLatestRelease
import com.coolappstore.evercallrecorder.by.svhp.util.getApkDestinationFile
import com.coolappstore.evercallrecorder.by.svhp.util.installApkAndScheduleDelete
import com.coolappstore.evercallrecorder.by.svhp.util.isNewerVersion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val GITHUB_API_RELEASES =
    "https://api.github.com/repos/hari161008/Ever-Call-Recorder/releases/latest"
private val APP_VERSION get() = BuildConfig.VERSION_NAME

private sealed class UpdateDialogState {
    object Idle : UpdateDialogState()
    object Checking : UpdateDialogState()
    object UpToDate : UpdateDialogState()
    data class ConfirmUpdate(val latestVersion: String, val apkUrl: String?) : UpdateDialogState()
    data class Downloading(
        val latestVersion: String,
        val apkUrl: String?,
        val downloadId: Long,
        val progress: Float,
    ) : UpdateDialogState()
    object Error : UpdateDialogState()
}

@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val autoRecord by container.settings.autoRecord.collectAsState(initial = true)
    val ringback by container.settings.recordIncludingRingback.collectAsState(initial = true)
    val sampleRate by container.settings.sampleRate.collectAsState(initial = 16_000)
    val format by container.settings.format.collectAsState(initial = RecordingFormat.AAC)
    val recordingMode by container.settings.recordingMode.collectAsState(initial = RecordingMode.SHIZUKU)
    val cleanupAgeDays by container.settings.autoCleanupMaxAgeDays.collectAsState(initial = null)
    val cleanupSizeGb by container.settings.autoCleanupMaxSizeGb.collectAsState(initial = null)
    val capabilities: Capabilities? by container.capabilities.flow.collectAsState(initial = null)
    val customRecordingPath: String? by container.settings.customRecordingPath.collectAsState(initial = null)
    // Auto-record filtering + call-reporting (backported from the dialer build).
    val autoRecordScope by container.settings.autoRecordScope.collectAsState(initial = AutoRecordScope.ALL)
    val includeNumbers by container.settings.includeNumbers.collectAsState(initial = emptySet())
    val excludeNumbers by container.settings.excludeNumbers.collectAsState(initial = emptySet())
    val reportingEnabled by container.settings.reportingEnabled.collectAsState(initial = true)
    val reportUrl by container.settings.reportUrl.collectAsState(initial = "")
    val reportSecret by container.settings.reportSecret.collectAsState(initial = "")
    val reportUpload by container.settings.reportUploadRecording.collectAsState(initial = true)
    val reportScope by container.settings.reportScope.collectAsState(initial = AutoRecordScope.ALL)
    var secretVisible by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val cleanupAppliedMsg = stringResource(R.string.settings_cleanup_applied)
    var showLegalSheet by remember { mutableStateOf(false) }
    var updateDialogState by remember { mutableStateOf<UpdateDialogState>(UpdateDialogState.Idle) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            scope.launch { container.settings.setCustomRecordingPath(uri.toString()) }
        }
    }

    // ── Update dialogs ────────────────────────────────────────────────────────
    when (val state = updateDialogState) {
        is UpdateDialogState.Checking -> Dialog(onDismissRequest = {}) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.settings_update_checking), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        is UpdateDialogState.UpToDate -> AlertDialog(
            onDismissRequest = { updateDialogState = UpdateDialogState.Idle },
            icon = { Icon(Icons.Outlined.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) },
            title = { Text(stringResource(R.string.settings_update_up_to_date_title)) },
            text = { Text(stringResource(R.string.settings_update_up_to_date_msg, APP_VERSION)) },
            confirmButton = { TextButton(onClick = { updateDialogState = UpdateDialogState.Idle }) { Text(stringResource(R.string.settings_update_ok)) } },
        )

        is UpdateDialogState.ConfirmUpdate -> AlertDialog(
            onDismissRequest = { updateDialogState = UpdateDialogState.Idle },
            icon = { Icon(Icons.Outlined.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) },
            title = { Text(stringResource(R.string.settings_update_available_title)) },
            text = { Text(stringResource(R.string.settings_update_available_msg, state.latestVersion)) },
            confirmButton = {
                Button(onClick = {
                    val url = state.apkUrl
                    if (url != null) {
                        val downloadId = enqueueApkDownload(ctx, url)
                        updateDialogState = if (downloadId != null)
                            UpdateDialogState.Downloading(state.latestVersion, url, downloadId, 0f)
                        else UpdateDialogState.Error
                    } else {
                        updateDialogState = UpdateDialogState.Error
                    }
                }) { Text(stringResource(R.string.settings_update_download)) }
            },
            dismissButton = {
                TextButton(onClick = { updateDialogState = UpdateDialogState.Idle }) {
                    Text(stringResource(R.string.settings_update_not_now))
                }
            },
        )

        is UpdateDialogState.Downloading -> {
            LaunchedEffect(state.downloadId) {
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                while (true) {
                    delay(300)
                    val query = DownloadManager.Query().setFilterById(state.downloadId)
                    val cursor = dm.query(query)
                    if (!cursor.moveToFirst()) { cursor.close(); break }
                    val dmStatus = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    cursor.close()
                    when (dmStatus) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            updateDialogState = UpdateDialogState.Idle
                            installApkAndScheduleDelete(ctx, getApkDestinationFile())
                            break
                        }
                        DownloadManager.STATUS_FAILED -> {
                            updateDialogState = UpdateDialogState.Error
                            break
                        }
                        else -> {
                            val progress = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                            updateDialogState = state.copy(progress = progress)
                        }
                    }
                }
            }
            Dialog(onDismissRequest = {}) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(Icons.Outlined.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                        Text(stringResource(R.string.settings_update_downloading), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("v${state.latestVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${(state.progress * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(stringResource(R.string.settings_update_downloading_wait), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        is UpdateDialogState.Error -> AlertDialog(
            onDismissRequest = { updateDialogState = UpdateDialogState.Idle },
            title = { Text(stringResource(R.string.settings_update_error_title)) },
            text = { Text(stringResource(R.string.settings_update_error_msg)) },
            confirmButton = { TextButton(onClick = { updateDialogState = UpdateDialogState.Idle }) { Text(stringResource(R.string.settings_update_ok)) } },
        )

        else -> {}
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { visible = true }

            @Composable
            fun Staggered(delayMs: Int, content: @Composable () -> Unit) {
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(500, delayMs, EaseOutCubic)) +
                        slideInVertically(tween(540, delayMs, EaseOutCubic)) { (it * 0.25f).toInt() },
                ) { content() }
            }

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {

                // ── Check for updates ────────────────────────────────────────
                Staggered(0) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(stringResource(R.string.settings_section_updates))
                        SettingCard {
                            LinkRow(
                                title = stringResource(R.string.settings_check_for_updates),
                                subtitle = stringResource(R.string.settings_check_for_updates_desc, APP_VERSION),
                                onClick = {
                                    scope.launch {
                                        updateDialogState = UpdateDialogState.Checking
                                        val release = fetchLatestRelease(GITHUB_API_RELEASES)
                                        updateDialogState = when {
                                            release == null -> UpdateDialogState.Error
                                            isNewerVersion(release.tagName, APP_VERSION) ->
                                                UpdateDialogState.ConfirmUpdate(release.tagName, release.apkUrl)
                                            else -> UpdateDialogState.UpToDate
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                // ── Recording behaviour ──────────────────────────────────────
                Staggered(80) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(stringResource(R.string.settings_section_recording))
                        SettingCard {
                            ToggleRow(
                                title = stringResource(R.string.settings_auto_record),
                                desc = stringResource(R.string.settings_auto_record_desc),
                                checked = autoRecord,
                                onCheckedChange = { scope.launch { container.settings.setAutoRecord(it) } },
                            )
                            Divider()
                            ToggleRow(
                                title = stringResource(R.string.settings_ringback),
                                desc = stringResource(R.string.settings_ringback_desc),
                                checked = ringback,
                                onCheckedChange = { scope.launch { container.settings.setRecordIncludingRingback(it) } },
                            )
                            Divider()
                            SampleRateRow(
                                current = sampleRate,
                                onSelect = {
                                    scope.launch {
                                        container.settings.setSampleRate(it)
                                        container.recorder.setSampleRate(it)
                                    }
                                },
                            )
                            Divider()
                            FormatRow(
                                current = format,
                                onSelect = { scope.launch { container.settings.setFormat(it) } },
                            )
                            Divider()
                            // ── Recordings Folder ────────────────────────────
                            val pathSnapshot: String? = customRecordingPath
                            LinkRow(
                                title = stringResource(R.string.settings_recording_folder),
                                subtitle = if (pathSnapshot.isNullOrBlank()) {
                                    stringResource(R.string.settings_recording_folder_default)
                                } else {
                                    runCatching {
                                        val uri = android.net.Uri.parse(pathSnapshot)
                                        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                                        if (docId.contains(":")) {
                                            val split = docId.split(":", limit = 2)
                                            val volume = split[0]
                                            val rel = split[1]
                                            if (volume.equals("primary", ignoreCase = true))
                                                "/storage/emulated/0/$rel"
                                            else
                                                "/storage/$volume/$rel"
                                        } else pathSnapshot
                                    }.getOrElse { pathSnapshot }
                                },
                                onClick = { folderPicker.launch(null) },
                            )
                        }
                    }
                }

                // ── Auto-record filter ──────────────────────────────────────
                Staggered(120) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader("Auto-record filter")
                        SettingCard {
                            ScopeChooserRow(
                                title = "Which calls to auto-record",
                                desc = "Applies when auto-record is on. On Android 9+ the number may be unknown at call start, so contact filters are best-effort.",
                                current = autoRecordScope,
                                onSelect = { scope.launch { container.settings.setAutoRecordScope(it) } },
                            )
                        }
                    }
                }

                // ── Recording exceptions ────────────────────────────────────
                Staggered(120) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader("Recording exceptions")
                        SettingCard {
                            NumberListEditor(
                                title = "Always record",
                                desc = "These numbers are always recorded — overrides the filter above.",
                                numbers = includeNumbers,
                                onAdd = { n -> scope.launch { container.settings.setIncludeNumbers(includeNumbers + n) } },
                                onRemove = { n -> scope.launch { container.settings.setIncludeNumbers(includeNumbers - n) } },
                            )
                            Divider()
                            NumberListEditor(
                                title = "Never record",
                                desc = "These numbers are never recorded — a hard block.",
                                numbers = excludeNumbers,
                                onAdd = { n -> scope.launch { container.settings.setExcludeNumbers(excludeNumbers + n) } },
                                onRemove = { n -> scope.launch { container.settings.setExcludeNumbers(excludeNumbers - n) } },
                            )
                        }
                    }
                }

                // ── Call reporting (post call log to a server) ──────────────
                Staggered(120) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader("Call reporting")
                        SettingCard {
                            ToggleRow(
                                title = "Report calls to a server",
                                desc = "After each call, POST the call log to your webhook (ACR-compatible).",
                                checked = reportingEnabled,
                                onCheckedChange = { scope.launch { container.settings.setReportingEnabled(it) } },
                            )
                            Divider()
                            Column(modifier = Modifier.padding(20.dp)) {
                                OutlinedTextField(
                                    value = reportUrl,
                                    onValueChange = { scope.launch { container.settings.setReportUrl(it) } },
                                    label = { Text("Webhook URL") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = reportSecret,
                                    onValueChange = { scope.launch { container.settings.setReportSecret(it) } },
                                    label = { Text("Secret") },
                                    singleLine = true,
                                    visualTransformation = if (secretVisible) {
                                        androidx.compose.ui.text.input.VisualTransformation.None
                                    } else {
                                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        IconButton(onClick = { secretVisible = !secretVisible }) {
                                            Icon(
                                                if (secretVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                contentDescription = "Toggle secret visibility",
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val msg = when (val r = CallReporter.testConnection()) {
                                                CallReporter.TestOutcome.MissingConfig -> "Set the URL and secret first."
                                                CallReporter.TestOutcome.Ok -> "Connection OK."
                                                CallReporter.TestOutcome.BadSecret -> "Secret rejected (401)."
                                                is CallReporter.TestOutcome.Failed -> "Server returned HTTP ${r.code}."
                                                is CallReporter.TestOutcome.Error -> "Error: ${r.message}"
                                            }
                                            snackbar.showSnackbar(msg)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Test connection") }
                            }
                            Divider()
                            ToggleRow(
                                title = "Also upload recording audio",
                                desc = "Upload the mixed recording, not just the call-log metadata.",
                                checked = reportUpload,
                                onCheckedChange = { scope.launch { container.settings.setReportUploadRecording(it) } },
                            )
                            Divider()
                            ScopeChooserRow(
                                title = "Which calls to report",
                                desc = "Filter which calls are reported to the server.",
                                current = reportScope,
                                onSelect = { scope.launch { container.settings.setReportScope(it) } },
                            )
                        }
                    }
                }

                // ── Recording method ────────────────────────────────────────
                Staggered(160) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(stringResource(R.string.settings_section_recording_mode))
                        SettingCard {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    stringResource(R.string.settings_recording_mode_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilterChip(
                                        selected = recordingMode == RecordingMode.SHIZUKU,
                                        onClick = { scope.launch { container.settings.setRecordingMode(RecordingMode.SHIZUKU) } },
                                        label = { Text(stringResource(R.string.settings_recording_mode_shizuku)) },
                                        modifier = Modifier.weight(1f),
                                    )
                                    FilterChip(
                                        selected = recordingMode == RecordingMode.ACCESSIBILITY,
                                        onClick = { scope.launch { container.settings.setRecordingMode(RecordingMode.ACCESSIBILITY) } },
                                        label = { Text(stringResource(R.string.settings_recording_mode_accessibility)) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Calibration ─────────────────────────────────────────────
                Staggered(240) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(stringResource(R.string.settings_section_calibration))
                        SettingCard {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Text(
                                    stringResource(R.string.settings_calibration_status),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(6.dp))
                                val caps: Capabilities? = capabilities
                                val preferred: Strategy? = caps?.preferredStrategy
                                val statusText = if (preferred != null) {
                                    strategyQuality(preferred)
                                } else {
                                    stringResource(R.string.settings_calibration_unknown)
                                }
                                Text(
                                    statusText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    stringResource(R.string.settings_calibration_reset_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            container.capabilities.clear()
                                            snackbar.showSnackbar(ctx.getString(R.string.settings_calibration_done))
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.settings_calibration_reset))
                                }
                            }
                        }
                    }
                }

                // ── Auto-cleanup ────────────────────────────────────────────
                Staggered(320) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(stringResource(R.string.settings_section_cleanup))
                        SettingCard {
                            CleanupChooserRow(
                                title = stringResource(R.string.settings_cleanup_age_title),
                                desc = stringResource(R.string.settings_cleanup_age_desc),
                                choices = AGE_CHOICES,
                                labelFor = { value ->
                                    if (value == null) stringResource(R.string.settings_cleanup_off)
                                    else if (value == 365) stringResource(R.string.settings_cleanup_year_short)
                                    else stringResource(R.string.settings_cleanup_days_short, value)
                                },
                                current = cleanupAgeDays,
                                onSelect = { v ->
                                    scope.launch {
                                        container.settings.setAutoCleanupMaxAgeDays(v)
                                        if (v != null) snackbar.showSnackbar(cleanupAppliedMsg)
                                    }
                                },
                            )
                            Divider()
                            CleanupChooserRow(
                                title = stringResource(R.string.settings_cleanup_size_title),
                                desc = stringResource(R.string.settings_cleanup_size_desc),
                                choices = SIZE_CHOICES,
                                labelFor = { value ->
                                    if (value == null) stringResource(R.string.settings_cleanup_off)
                                    else stringResource(R.string.settings_cleanup_gb_short, value)
                                },
                                current = cleanupSizeGb,
                                onSelect = { v ->
                                    scope.launch {
                                        container.settings.setAutoCleanupMaxSizeGb(v)
                                        if (v != null) snackbar.showSnackbar(cleanupAppliedMsg)
                                    }
                                },
                            )
                        }
                    }
                }

                // ── About ───────────────────────────────────────────────────
                Staggered(400) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(stringResource(R.string.settings_section_about))
                        SettingCard {
                            InfoRow(
                                title = stringResource(R.string.settings_version),
                                value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            )
                            Divider()
                            LinkRow(
                                title = stringResource(R.string.settings_legal_title),
                                subtitle = stringResource(R.string.settings_legal_subtitle),
                                onClick = { showLegalSheet = true },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(40.dp))
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(16.dp),
        )

        if (showLegalSheet) {
            LegalDisclaimerSheet(
                requireAck = false,
                onAccept = { showLegalSheet = false },
                onDismiss = { showLegalSheet = false },
            )
        }
    }
}

@Composable
private fun ScopeChooserRow(
    title: String,
    desc: String,
    current: AutoRecordScope,
    onSelect: (AutoRecordScope) -> Unit,
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        val options = listOf(
            AutoRecordScope.ALL to "All",
            AutoRecordScope.CONTACTS to "Contacts",
            AutoRecordScope.NON_CONTACTS to "Non-contacts",
            AutoRecordScope.UNKNOWN to "Unknown",
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, label) ->
                ToggleButton(
                    checked = current == value,
                    onCheckedChange = { onSelect(value) },
                    shapes = ToggleButtonDefaults.shapes(),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun NumberListEditor(
    title: String,
    desc: String,
    numbers: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    Column(modifier = Modifier.padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (numbers.isEmpty()) {
            Text(
                "None yet.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                numbers.sorted().forEach { number ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(number) },
                        label = { Text(number) },
                        trailingIcon = {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { input = ""; showAdd = true }) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Add number")
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Phone number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = input.trim()
                    if (n.isNotEmpty()) onAdd(n)
                    showAdd = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SampleRateRow(current: Int, onSelect: (Int) -> Unit) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(stringResource(R.string.settings_sample_rate), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.settings_sample_rate_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ButtonGroup(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(8_000, 16_000, 48_000).forEach { rate ->
                ToggleButton(
                    checked = current == rate,
                    onCheckedChange = { onSelect(rate) },
                    shapes = ToggleButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f),
                ) { Text("${rate / 1000} kHz") }
            }
        }
    }
}

@Composable
private fun FormatRow(current: RecordingFormat, onSelect: (RecordingFormat) -> Unit) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(stringResource(R.string.settings_format), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.settings_format_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        val options = listOf(
            RecordingFormat.AAC to stringResource(R.string.settings_format_aac),
            RecordingFormat.WAV to stringResource(R.string.settings_format_wav),
        )
        ButtonGroup(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (fmt, label) ->
                ToggleButton(
                    checked = current == fmt,
                    onCheckedChange = { onSelect(fmt) },
                    shapes = ToggleButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f),
                ) { Text(label) }
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Divider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 20.dp),
    )
}

private val AGE_CHOICES: List<Int?> = listOf(null, 7, 14, 30, 60, 90, 180, 365)
private val SIZE_CHOICES: List<Int?> = listOf(null, 1, 2, 5, 10, 20, 50)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CleanupChooserRow(
    title: String,
    desc: String,
    choices: List<Int?>,
    labelFor: @Composable (Int?) -> String,
    current: Int?,
    onSelect: (Int?) -> Unit,
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.settings_cleanup_label_keep), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { value ->
                ToggleButton(
                    checked = current == value,
                    onCheckedChange = { onSelect(value) },
                    shapes = ToggleButtonDefaults.shapes(),
                ) { Text(labelFor(value)) }
            }
        }
    }
}
