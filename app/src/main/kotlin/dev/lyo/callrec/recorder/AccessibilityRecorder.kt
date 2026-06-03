// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.recorder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import com.coolappstore.evercallrecorder.by.svhp.codec.AacEncoder
import com.coolappstore.evercallrecorder.by.svhp.codec.AudioLevelMeter
import com.coolappstore.evercallrecorder.by.svhp.codec.PcmEncoder
import com.coolappstore.evercallrecorder.by.svhp.codec.WavEncoder
import com.coolappstore.evercallrecorder.by.svhp.core.L
import com.coolappstore.evercallrecorder.by.svhp.settings.RecordingFormat
import com.coolappstore.evercallrecorder.by.svhp.storage.RecordingFile
import com.coolappstore.evercallrecorder.by.svhp.storage.RecordingStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Records calls directly using [AudioRecord] — no Shizuku/root required.
 *
 * Source ladder (for call recording):
 *  1. VOICE_COMMUNICATION — captures mic on all devices; on most OEMs it also
 *     captures the remote side via acoustic sidetone / modem mix.
 *  2. MIC — guaranteed uplink-only fallback.
 *
 * Note: VOICE_CALL requires the system permission CAPTURE_AUDIO_OUTPUT on
 * Android 10+ and will always throw SecurityException for third-party apps.
 * It is intentionally excluded from this ladder.
 *
 * The Accessibility Service grants the FGS-from-background exemption so that
 * CallStateReceiver can call startForegroundService() from a BroadcastReceiver.
 * It does NOT grant any additional audio capture privileges.
 */
class AccessibilityRecorder(
    private val storage: RecordingStorage,
    private val scope: CoroutineScope,
    private val sampleRate: Int = 16_000,
    private val formatProvider: () -> RecordingFormat = { RecordingFormat.AAC },
) {
    sealed interface RecordingState {
        data object Idle : RecordingState
        data class Probing(val source: String) : RecordingState
        data class Active(val outcome: Outcome) : RecordingState
        data class Failed(val reason: String) : RecordingState
    }

    sealed interface Outcome {
        data class Single(val file: RecordingFile, val strategy: Strategy) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    data class Levels(
        val rms: Float = 0f,
        val totalFrames: Long = 0L,
        val voiceMemo: Boolean = false,
    )

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state

    private val _levels = MutableStateFlow(Levels())
    val levels: StateFlow<Levels> = _levels

    @Volatile private var activeAr: AudioRecord? = null
    @Volatile private var activePumpThread: Thread? = null
    @Volatile private var activeOutcome: Outcome? = null
    @Volatile private var stopping = false
    private var watchdog: Job? = null

    private fun ext() = if (formatProvider() == RecordingFormat.WAV) "wav" else "m4a"
    private fun newEncoder(f: RecordingFile): PcmEncoder = when (formatProvider()) {
        RecordingFormat.WAV -> WavEncoder(f)
        RecordingFormat.AAC -> AacEncoder(f)
    }

    fun start(callId: String, voiceMemo: Boolean = false) {
        scope.launch(Dispatchers.Default) { startInternal(callId, voiceMemo) }
    }

    private suspend fun startInternal(callId: String, voiceMemo: Boolean) {
        if (activeOutcome != null) {
            L.w(TAG, "start() while already active — ignored")
            return
        }
        stopping = false
        _levels.value = Levels(voiceMemo = voiceMemo)

        // VOICE_CALL requires system privilege; never include it here.
        val sources = if (voiceMemo) {
            listOf(AudioSource.MIC)
        } else {
            listOf(AudioSource.VOICE_COMMUNICATION, AudioSource.MIC)
        }

        for ((idx, source) in sources.withIndex()) {
            if (stopping) return
            val isFinalFallback = (idx == sources.lastIndex)
            val sourceName = audioSourceName(source)
            _state.value = RecordingState.Probing(sourceName)
            L.i(TAG, "Trying $sourceName (finalFallback=$isFinalFallback)")

            // 1. Determine buffer size
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                L.w(TAG, "$sourceName: getMinBufferSize=$minBuf — skipping")
                continue
            }
            val bufSize = maxOf(minBuf * 4, 16 * 1024)

            // 2. Create AudioRecord
            val ar = try {
                AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize)
            } catch (e: Exception) {
                L.w(TAG, "$sourceName: AudioRecord() threw ${e.message}")
                continue
            }
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                L.w(TAG, "$sourceName: state=${ar.state} — skipping")
                ar.release()
                continue
            }

            // 3. Create file and open encoder BEFORE starting the AudioRecord
            val strategy = if (source == AudioSource.VOICE_COMMUNICATION)
                Strategy.SingleVoiceCallMono else Strategy.SingleMic
            val file = storage.create(callId, "rec_${sourceName.lowercase()}", ext())
            val encoder = newEncoder(file)
            try {
                encoder.open(sampleRate, 1)
            } catch (e: Exception) {
                L.e(TAG, "$sourceName: encoder.open() failed: ${e.message}")
                ar.release()
                continue
            }

            // 4. Start recording
            try {
                ar.startRecording()
            } catch (e: SecurityException) {
                L.e(TAG, "$sourceName: RECORD_AUDIO denied: ${e.message}")
                runCatching { encoder.close() }
                ar.release()
                _state.value = RecordingState.Failed("RECORD_AUDIO permission denied")
                return
            } catch (e: Exception) {
                L.e(TAG, "$sourceName: startRecording() failed: ${e.message}")
                runCatching { encoder.close() }
                ar.release()
                continue
            }
            if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                L.w(TAG, "$sourceName: recordingState=${ar.recordingState} after start")
                runCatching { encoder.close() }
                runCatching { ar.stop() }
                ar.release()
                continue
            }

            // 5. Spin up pump thread
            val meter = AudioLevelMeter(sampleRate)
            val thread = Thread({
                val buf = ByteArray(bufSize)
                try {
                    while (!stopping) {
                        val n = ar.read(buf, 0, buf.size)
                        when {
                            n > 0 -> {
                                encoder.writePcm(buf, 0, n)
                                meter.update(buf, 0, n)
                            }
                            n == AudioRecord.ERROR_INVALID_OPERATION -> {
                                L.w(TAG, "$sourceName read() ERROR_INVALID_OPERATION")
                                break
                            }
                            n == AudioRecord.ERROR -> {
                                L.w(TAG, "$sourceName read() ERROR")
                                break
                            }
                            else -> break
                        }
                    }
                } finally {
                    runCatching { encoder.close() }
                    runCatching { ar.stop() }
                    runCatching { ar.release() }
                    L.i(TAG, "$sourceName pump finished")
                }
            }, "callrec-acc-$sourceName").apply { isDaemon = true; start() }

            activeAr = ar
            activePumpThread = thread

            // 6. Silence check — skip for voice memos and final fallback
            val audible = if (voiceMemo || isFinalFallback) {
                true
            } else {
                waitForAudible(meter)
            }

            if (!audible && !stopping) {
                L.w(TAG, "$sourceName silent after ${SILENCE_GRACE_MS}ms — trying next")
                // Stop current source cleanly before trying next
                stopping = true
                runCatching { ar.stop() }
                withContext(Dispatchers.IO) { thread.join(3_000) }
                stopping = false
                activeAr = null
                activePumpThread = null
                continue
            }

            // 7. Source is good — adopt it
            val outcome = Outcome.Single(file, strategy)
            activeOutcome = outcome
            _state.value = RecordingState.Active(outcome)
            startWatchdog(meter)
            L.i(TAG, "$sourceName adopted (audible=$audible)")
            return
        }

        if (activeOutcome == null && !stopping) {
            _state.value = RecordingState.Failed("No audio source produced a recording")
        }
    }

    /**
     * Polls the meter for up to [SILENCE_GRACE_MS]. Returns true as soon as
     * any voice is detected, false if the whole window passed in silence.
     */
    private suspend fun waitForAudible(meter: AudioLevelMeter): Boolean {
        val deadline = System.currentTimeMillis() + SILENCE_GRACE_MS
        while (System.currentTimeMillis() < deadline && !stopping) {
            delay(LEVEL_TICK_MS)
            _levels.value = _levels.value.copy(rms = meter.lastRms, totalFrames = meter.totalFrames)
            if (meter.isAudible) {
                L.i(TAG, "Audible signal detected rms=${meter.lastRms}")
                return true
            }
        }
        return false
    }

    private fun startWatchdog(meter: AudioLevelMeter) {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (activeOutcome != null && !stopping) {
                _levels.value = _levels.value.copy(
                    rms = meter.lastRms,
                    totalFrames = meter.totalFrames,
                )
                delay(LEVEL_TICK_MS)
            }
        }
    }

    suspend fun stop(): Outcome? {
        stopping = true
        watchdog?.cancel()
        watchdog = null
        withContext(Dispatchers.IO) {
            runCatching { activeAr?.stop() }
            activePumpThread?.join(5_000)
        }
        activeAr = null
        activePumpThread = null
        val out = activeOutcome
        activeOutcome = null
        _levels.value = Levels()
        _state.value = RecordingState.Idle
        return out
    }

    private fun audioSourceName(source: Int) = when (source) {
        AudioSource.VOICE_COMMUNICATION -> "VOICE_COMM"
        AudioSource.MIC -> "MIC"
        else -> "source($source)"
    }

    companion object {
        private const val TAG = "AccessibilityRecorder"
        private const val SILENCE_GRACE_MS = 5_000L
        private const val LEVEL_TICK_MS = 60L
    }
}
