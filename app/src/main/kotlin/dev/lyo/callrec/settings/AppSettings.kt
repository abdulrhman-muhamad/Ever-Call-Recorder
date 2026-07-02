// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class RecordingFormat { WAV, AAC }

enum class RecordingMode { SHIZUKU, ACCESSIBILITY }

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
    }
}
