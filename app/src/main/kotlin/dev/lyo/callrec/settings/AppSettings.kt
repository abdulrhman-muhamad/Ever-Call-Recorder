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
import com.coolappstore.evercallrecorder.by.svhp.storage.RecordingFileNameFormatter
import com.coolappstore.evercallrecorder.by.svhp.security.AppLockCrypto

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

/**
 * Ordering of the recordings library. [CallDao.observeSearch] always returns
 * newest-first, so every other ordering is applied in memory by the screen —
 * the list is bounded by the cleanup policy, and one query keeps serving all
 * four orderings without a second DAO method per sort.
 *
 * Duration sorts derive length from `ended_at - started_at`; an in-flight row
 * (`ended_at` still NULL) sorts as zero-length rather than being dropped.
 */
enum class RecordingSort { NEWEST, OLDEST, LONGEST, SHORTEST }

/** How the app lock challenges the user. NONE = lock disabled. */
enum class AppLockMethod { NONE, PIN, PASSWORD, BIOMETRIC }

/**
 * Snapshot of the app-lock configuration. [verify] is synchronous on purpose:
 * the lock screen and the settings verify dialog call it from plain lambdas,
 * so the (tiny) hash+salt are read once into this object rather than hitting
 * DataStore per keystroke. Biometric has no secret — [verify] is always false
 * for it; the BiometricPrompt result is the verification.
 */
data class AppLockState(
    val enabled: Boolean,
    val method: AppLockMethod,
    private val hash: String?,
    private val salt: String?,
) {
    fun verify(secret: String): Boolean =
        (method == AppLockMethod.PIN || method == AppLockMethod.PASSWORD) &&
            hash != null && salt != null && AppLockCrypto.verify(secret, salt, hash)
}

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

    // Library sort order. Unparseable values (a downgrade that wrote an enum
    // constant this build doesn't know) fall back to NEWEST rather than
    // throwing — same defensive read as `format` above.
    val sortOrder: Flow<RecordingSort> = store.data.map {
        runCatching { RecordingSort.valueOf(it[Keys.SORT_ORDER] ?: RecordingSort.NEWEST.name) }
            .getOrDefault(RecordingSort.NEWEST)
    }
    suspend fun setSortOrder(v: RecordingSort) = store.edit { it[Keys.SORT_ORDER] = v.name }

    // Name given to recordings on the way OUT (share sheet, zip export) — the
    // on-disk name stays `<ts>__<callId>__<tag>` because it is minted at call
    // start, before the contact is resolved. See RecordingFileNameFormatter.
    val exportNameTemplate: Flow<String> = store.data.map {
        it[Keys.EXPORT_NAME_TEMPLATE]?.takeIf { t -> t.isNotBlank() }
            ?: RecordingFileNameFormatter.DEFAULT_TEMPLATE
    }
    suspend fun setExportNameTemplate(v: String) = store.edit { it[Keys.EXPORT_NAME_TEMPLATE] = v }

    // App lock. The secret is never stored — only a salted SHA-256 (see
    // AppLockCrypto). Method is validated on read so a downgrade can't leave
    // the gate stuck on an unknown constant: unknown → NONE → lock disabled.
    val appLockState: Flow<AppLockState> = store.data.map {
        val method = runCatching { AppLockMethod.valueOf(it[Keys.APP_LOCK_METHOD] ?: AppLockMethod.NONE.name) }
            .getOrDefault(AppLockMethod.NONE)
        val enabled = (it[Keys.APP_LOCK_ENABLED] ?: false) && method != AppLockMethod.NONE
        AppLockState(enabled, method, it[Keys.APP_LOCK_HASH], it[Keys.APP_LOCK_SALT])
    }
    suspend fun setAppLockSecret(method: AppLockMethod, secret: String) {
        require(method == AppLockMethod.PIN || method == AppLockMethod.PASSWORD)
        val salt = AppLockCrypto.generateSalt()
        val hash = AppLockCrypto.hash(secret, salt)
        store.edit {
            it[Keys.APP_LOCK_SALT] = salt
            it[Keys.APP_LOCK_HASH] = hash
            it[Keys.APP_LOCK_METHOD] = method.name
            it[Keys.APP_LOCK_ENABLED] = true
        }
    }
    suspend fun setAppLockBiometric() = store.edit {
        it.remove(Keys.APP_LOCK_SALT)
        it.remove(Keys.APP_LOCK_HASH)
        it[Keys.APP_LOCK_METHOD] = AppLockMethod.BIOMETRIC.name
        it[Keys.APP_LOCK_ENABLED] = true
    }
    suspend fun clearAppLock() = store.edit {
        it[Keys.APP_LOCK_ENABLED] = false
        it[Keys.APP_LOCK_METHOD] = AppLockMethod.NONE.name
        it.remove(Keys.APP_LOCK_SALT)
        it.remove(Keys.APP_LOCK_HASH)
    }

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
        val SORT_ORDER = stringPreferencesKey("library_sort_order")
        val EXPORT_NAME_TEMPLATE = stringPreferencesKey("export_name_template")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_METHOD = stringPreferencesKey("app_lock_method")
        val APP_LOCK_HASH = stringPreferencesKey("app_lock_secret_hash")
        val APP_LOCK_SALT = stringPreferencesKey("app_lock_salt")
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
