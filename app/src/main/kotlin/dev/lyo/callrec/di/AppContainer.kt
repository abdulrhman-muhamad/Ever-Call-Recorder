// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.coolappstore.evercallrecorder.by.svhp.playback.MediaSessionHolder
import com.coolappstore.evercallrecorder.by.svhp.recorder.AccessibilityRecorder
import com.coolappstore.evercallrecorder.by.svhp.recorder.CapabilitiesStore
import com.coolappstore.evercallrecorder.by.svhp.recorder.RecorderController
import com.coolappstore.evercallrecorder.by.svhp.recorder.ShizukuClient
import com.coolappstore.evercallrecorder.by.svhp.settings.RecordingFormat
import com.coolappstore.evercallrecorder.by.svhp.settings.RecordingMode
import com.coolappstore.evercallrecorder.by.svhp.storage.RecordingStorage
import com.coolappstore.evercallrecorder.by.svhp.storage.RecordingsDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import com.coolappstore.evercallrecorder.by.svhp.settings.AppSettings

private val Context.dataStore by preferencesDataStore(name = "callrec.settings")

class AppContainer(private val ctx: Context) {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: AppSettings by lazy { AppSettings(ctx.dataStore) }

    val db: RecordingsDb by lazy { RecordingsDb.create(ctx) }

    val storage: RecordingStorage by lazy {
        RecordingStorage(
            appCtx = ctx.applicationContext,
            settings = settings,
        )
    }

    val shizuku: ShizukuClient by lazy { ShizukuClient(ctx.applicationContext) }

    val capabilities: CapabilitiesStore by lazy { CapabilitiesStore(ctx.applicationContext) }

    val recordingFormat: StateFlow<RecordingFormat> by lazy {
        settings.format.stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = RecordingFormat.AAC,
        )
    }

    val recordingMode: StateFlow<RecordingMode> by lazy {
        settings.recordingMode.stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = RecordingMode.SHIZUKU,
        )
    }

    val recorder: RecorderController by lazy {
        RecorderController(
            client = shizuku,
            storage = storage,
            capabilities = capabilities,
            scope = appScope,
            formatProvider = { recordingFormat.value },
        )
    }

    val accessibilityRecorder: AccessibilityRecorder by lazy {
        AccessibilityRecorder(
            storage = storage,
            scope = appScope,
            formatProvider = { recordingFormat.value },
        )
    }

    val mediaSession: MediaSessionHolder by lazy { MediaSessionHolder(ctx.applicationContext) }
}
