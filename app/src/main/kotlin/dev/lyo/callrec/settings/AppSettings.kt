// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.coolappstore.evercallrecorder.by.svhp.core.CryptoBox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class RecordingFormat { WAV, AAC }

enum class RecordingMode { SHIZUKU, ACCESSIBILITY }

/**
 * Which calls auto-record (and reporting) applies to — a single exclusive choice.
 *  - ALL: every eligible call.
 *  - CONTACTS: only numbers saved in Contacts.
 *  - NON_CONTACTS: only numbers NOT saved in Contacts (caller ID present).
 *  - UNKNOWN: only calls with no caller ID (private/withheld number).
 */
enum class AutoRecordScope { ALL, CONTACTS, NON_CONTACTS, UNKNOWN }

class AppSettings(private val store: DataStore<Preferences>) {

    val sampleRate: Flow<Int> = store.data.map { it[Keys.SAMPLE_RATE] ?: 16_000 }
    suspend fun setSampleRate(v: Int) = store.edit { it[Keys.SAMPLE_RATE] = v }

    val autoRecord: Flow<Boolean> = store.data.map { it[Keys.AUTO_RECORD] ?: true }
    suspend fun setAutoRecord(v: Boolean) = store.edit { it[Keys.AUTO_RECORD] = v }

    val onboardingDone: Flow<Boolean> = store.data.map { it[Keys.ONBOARDING_DONE] ?: false }
    suspend fun setOnboardingDone(v: Boolean) = store.edit { it[Keys.ONBOARDING_DONE] = v }

    val disclaimerAccepted: Flow<Boolean> = store.data.map { it[Keys.DISCLAIMER_ACCEPTED] ?: false }
    suspend fun setDisclaimerAccepted(v: Boolean) = store.edit { it[Keys.DISCLAIMER_ACCEPTED] = v }

    val recordIncludingRingback: Flow<Boolean> = store.data.map { it[Keys.RING_INCLUDED] ?: false }
    suspend fun setRecordIncludingRingback(v: Boolean) = store.edit { it[Keys.RING_INCLUDED] = v }

    // Xiaomi/MIUI-only acknowledgment. MIUI's proprietary permissions (autostart,
    // show-on-lock-screen, background pop-up, shortcut) can't be read back
    // reliably, so the onboarding gate trusts this flag — set when the user
    // confirms they've enabled them (after our best-effort Shizuku auto-grant
    // and/or the deep-link to MIUI's own settings).
    val miuiPermsAcknowledged: Flow<Boolean> = store.data.map { it[Keys.MIUI_ACK] ?: false }
    suspend fun setMiuiPermsAcknowledged(v: Boolean) = store.edit { it[Keys.MIUI_ACK] = v }

    val format: Flow<RecordingFormat> = store.data.map {
        runCatching { RecordingFormat.valueOf(it[Keys.FORMAT] ?: RecordingFormat.AAC.name) }
            .getOrDefault(RecordingFormat.AAC)
    }
    suspend fun setFormat(v: RecordingFormat) = store.edit { it[Keys.FORMAT] = v.name }

    val recordingMode: Flow<RecordingMode> = store.data.map {
        runCatching { RecordingMode.valueOf(it[Keys.RECORDING_MODE] ?: RecordingMode.SHIZUKU.name) }
            .getOrDefault(RecordingMode.SHIZUKU)
    }
    suspend fun setRecordingMode(v: RecordingMode) = store.edit { it[Keys.RECORDING_MODE] = v.name }

    val autoCleanupMaxAgeDays: Flow<Int?> = store.data.map {
        val v = it[Keys.CLEANUP_MAX_AGE_DAYS] ?: 0
        if (v <= 0) null else v
    }
    suspend fun setAutoCleanupMaxAgeDays(v: Int?) = store.edit {
        if (v == null || v <= 0) it.remove(Keys.CLEANUP_MAX_AGE_DAYS)
        else it[Keys.CLEANUP_MAX_AGE_DAYS] = v
    }

    val autoCleanupMaxSizeGb: Flow<Int?> = store.data.map {
        val v = it[Keys.CLEANUP_MAX_SIZE_GB] ?: 0
        if (v <= 0) null else v
    }
    suspend fun setAutoCleanupMaxSizeGb(v: Int?) = store.edit {
        if (v == null || v <= 0) it.remove(Keys.CLEANUP_MAX_SIZE_GB)
        else it[Keys.CLEANUP_MAX_SIZE_GB] = v
    }

    /** Null means "use default path" (getExternalFilesDir/.../recordings). */
    val customRecordingPath: Flow<String?> = store.data.map { it[Keys.CUSTOM_RECORDING_PATH] }
    suspend fun setCustomRecordingPath(v: String?) = store.edit {
        if (v == null) it.remove(Keys.CUSTOM_RECORDING_PATH)
        else it[Keys.CUSTOM_RECORDING_PATH] = v
    }

    // ── Auto-record filtering ──────────────────────────────────────────────

    val autoRecordScope: Flow<AutoRecordScope> = store.data.map {
        runCatching { AutoRecordScope.valueOf(it[Keys.AUTO_RECORD_SCOPE] ?: AutoRecordScope.ALL.name) }
            .getOrDefault(AutoRecordScope.ALL)
    }
    suspend fun setAutoRecordScope(v: AutoRecordScope) =
        store.edit { it[Keys.AUTO_RECORD_SCOPE] = v.name }

    /**
     * PhoneAccountHandle id to restrict auto-record to a single SIM. `null`
     * means "any SIM" and is the default. Only relevant on dual-SIM devices.
     */
    val autoRecordSimId: Flow<String?> = store.data.map { it[Keys.AUTO_RECORD_SIM_ID] }
    suspend fun setAutoRecordSimId(v: String?) = store.edit {
        if (v.isNullOrBlank()) it.remove(Keys.AUTO_RECORD_SIM_ID) else it[Keys.AUTO_RECORD_SIM_ID] = v
    }

    /** Numbers to ALWAYS auto-record — overrides SIM filter and scope. */
    val includeNumbers: Flow<Set<String>> = store.data.map { it[Keys.INCLUDE_NUMBERS] ?: emptySet() }
    suspend fun setIncludeNumbers(v: Set<String>) = store.edit { it[Keys.INCLUDE_NUMBERS] = v }

    /** Numbers to NEVER record — a hard block, honoured even for a manual tap. */
    val excludeNumbers: Flow<Set<String>> = store.data.map { it[Keys.EXCLUDE_NUMBERS] ?: emptySet() }
    suspend fun setExcludeNumbers(v: Set<String>) = store.edit { it[Keys.EXCLUDE_NUMBERS] = v }

    // ── Call reporting (post a call-log entry to a server after each call) ──

    val reportingEnabled: Flow<Boolean> = store.data.map { it[Keys.REPORT_ENABLED] ?: true }
    suspend fun setReportingEnabled(v: Boolean) = store.edit { it[Keys.REPORT_ENABLED] = v }

    /** Full acr-webhook URL, e.g. https://host/api/acr-calls/acr-webhook */
    val reportUrl: Flow<String> = store.data.map { it[Keys.REPORT_URL] ?: "" }
    suspend fun setReportUrl(v: String) = store.edit { it[Keys.REPORT_URL] = v.trim() }

    /** Per-user secret (acr_…) — stored encrypted via CryptoBox. */
    val reportSecret: Flow<String> = store.data.map {
        val raw = it[Keys.REPORT_SECRET] ?: ""
        if (raw.isBlank()) "" else CryptoBox.decryptOrPassthrough(raw)
    }
    suspend fun setReportSecret(v: String) = store.edit {
        if (v.isBlank()) it.remove(Keys.REPORT_SECRET) else it[Keys.REPORT_SECRET] = CryptoBox.encrypt(v.trim())
    }

    val reportScope: Flow<AutoRecordScope> = store.data.map {
        runCatching { AutoRecordScope.valueOf(it[Keys.REPORT_SCOPE] ?: AutoRecordScope.ALL.name) }
            .getOrDefault(AutoRecordScope.ALL)
    }
    suspend fun setReportScope(v: AutoRecordScope) = store.edit { it[Keys.REPORT_SCOPE] = v.name }

    /** PhoneAccountHandle id to report from a single SIM; null = any SIM. */
    val reportSimId: Flow<String?> = store.data.map { it[Keys.REPORT_SIM_ID] }
    suspend fun setReportSimId(v: String?) = store.edit {
        if (v.isNullOrBlank()) it.remove(Keys.REPORT_SIM_ID) else it[Keys.REPORT_SIM_ID] = v
    }

    /** Also upload the recording audio (not just the call-log metadata). */
    val reportUploadRecording: Flow<Boolean> = store.data.map { it[Keys.REPORT_UPLOAD] ?: true }
    suspend fun setReportUploadRecording(v: Boolean) = store.edit { it[Keys.REPORT_UPLOAD] = v }

    companion object

    private object Keys {
        val SAMPLE_RATE = intPreferencesKey("sample_rate")
        val AUTO_RECORD = booleanPreferencesKey("auto_record")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted_v1")
        val RING_INCLUDED = booleanPreferencesKey("ring_included")
        val MIUI_ACK = booleanPreferencesKey("miui_perms_acknowledged_v1")
        val FORMAT = stringPreferencesKey("recording_format")
        val RECORDING_MODE = stringPreferencesKey("recording_mode")
        val CLEANUP_MAX_AGE_DAYS = intPreferencesKey("auto_cleanup_max_age_days")
        val CLEANUP_MAX_SIZE_GB = intPreferencesKey("auto_cleanup_max_size_gb")
        val CUSTOM_RECORDING_PATH = stringPreferencesKey("custom_recording_path")
        val AUTO_RECORD_SCOPE = stringPreferencesKey("auto_record_scope")
        val AUTO_RECORD_SIM_ID = stringPreferencesKey("auto_record_sim_id")
        val INCLUDE_NUMBERS = stringSetPreferencesKey("auto_record_include_numbers")
        val EXCLUDE_NUMBERS = stringSetPreferencesKey("auto_record_exclude_numbers")
        val REPORT_ENABLED = booleanPreferencesKey("report_enabled")
        val REPORT_URL = stringPreferencesKey("report_url")
        val REPORT_SECRET = stringPreferencesKey("report_secret")
        val REPORT_SCOPE = stringPreferencesKey("report_scope")
        val REPORT_SIM_ID = stringPreferencesKey("report_sim_id")
        val REPORT_UPLOAD = booleanPreferencesKey("report_upload_recording")
    }
}
