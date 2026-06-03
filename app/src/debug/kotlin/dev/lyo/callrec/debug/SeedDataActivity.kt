// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.coolappstore.evercallrecorder.by.svhp.App
import com.coolappstore.evercallrecorder.by.svhp.recorder.Strategy
import com.coolappstore.evercallrecorder.by.svhp.storage.CallDao
import com.coolappstore.evercallrecorder.by.svhp.storage.CallRecord
import com.coolappstore.evercallrecorder.by.svhp.telephony.CallMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Debug-only entrypoint that wipes the recordings DB and seeds 8 plausible
 * sample calls so we can grab clean screenshots. Trigger from a host shell:
 *
 *   adb shell am start -n com.coolappstore.evercallrecorder.by.svhp.debug/com.coolappstore.evercallrecorder.by.svhp.debug.SeedDataActivity
 *
 * Lives under `app/src/debug/` so it cannot ship in a release build.
 *
 * Each fake recording gets a tiny silent WAV (~96 KB) on disk so the playback
 * screen and MediaPlayer prepare path don't crash if we tap into one for the
 * screenshot.
 */
class SeedDataActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity is exported=true (required for shell `am start` to work on
        // Samsung One UI 16+ debug builds), so guard at runtime: only honour
        // invocations from the shell or from our own package. Without this
        // any other on-device app on a debug install could wipe the
        // recordings DB by firing an Intent at us.
        //
        // We use Activity.referrer (Uri "android-app://<pkg>") instead of
        // launchedFromUid because the latter is unreliable across vendors —
        // verified to return -1 on Samsung One UI 16 (API 36) for shell
        // invocations even though minSdk 31 supposedly exposes it.
        val ref = referrer?.host
        val trusted = ref == "com.android.shell" || ref == packageName
        if (!trusted) {
            Log.w(TAG, "rejected: referrer=$ref")
            Toast.makeText(this, "SeedDataActivity: untrusted caller blocked", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Log.i(TAG, "trusted referrer=$ref — running seed")
        scope.launch {
            val count = withContext(Dispatchers.IO) { runSeed() }
            Toast.makeText(this@SeedDataActivity, "Seeded $count records", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private suspend fun runSeed(): Int {
        val app = application as App
        val container = app.container
        val dao = container.db.calls()

        val baseDir = getExternalFilesDir(null)?.resolve("recordings")
            ?: filesDir.resolve("recordings")
        baseDir.mkdirs()
        Log.i(TAG, "seeding into ${baseDir.absolutePath}")

        // Wipe existing records — we want a clean slate for screenshots.
        val all = dao.observeAllOnce()
        val ids = all.map { it.callId }
        if (ids.isNotEmpty()) {
            dao.deleteAll(ids)
            // Don't bother deleting on-disk audio for the prior seeded set —
            // they're tiny silent WAVs in the same dir, harmless.
        }

        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000

        val seeds = listOf(
            // ─── Today ──────────────────────────────────────────────────────
            Seed(
                offsetMs = -2 * 60 * 60 * 1000L, // today, 2h ago — voice memo with smart title
                durSec = 87,
                name = null,
                number = null,
                modeOverride = CallMonitorService.MODE_VOICE_MEMO,
                strategy = Strategy.SingleMic,
            ),
            Seed(
                offsetMs = -3 * 60 * 60 * 1000L, // today, 3h ago
                durSec = 222, // 3:42
                name = "Andrew Kovalenko",
                number = "+380671234567",
                strategy = Strategy.DualUplinkDownlink,
                favorite = true,
                notes = "Discussed price for the batch. He calls tomorrow with a decision.",
            ),
            Seed(
                offsetMs = -6 * 60 * 60 * 1000L, // today, 6h ago — phone call, new schema
                durSec = 738, // 12:18
                name = "Mom",
                number = "+380505551234",
                strategy = Strategy.DualUplinkDownlink,
                favorite = true,
            ),
            Seed(
                offsetMs = -8 * 60 * 60 * 1000L, // today, 8h ago — voice memo (no transcript yet)
                durSec = 24,
                name = null,
                number = null,
                modeOverride = CallMonitorService.MODE_VOICE_MEMO,
                strategy = Strategy.SingleMic,
            ),
            // ─── Yesterday ──────────────────────────────────────────────────
            Seed(
                offsetMs = -1 * day - 4 * 60 * 60 * 1000L, // yesterday — 4-speaker meeting
                durSec = 1245,
                name = null,
                number = null,
                modeOverride = CallMonitorService.MODE_VOICE_MEMO,
                strategy = Strategy.SingleMic,
            ),
            Seed(
                offsetMs = -1 * day - 7 * 60 * 60 * 1000L, // yesterday
                durSec = 321,
                name = "Elena (Mono)",
                number = "+380442000001",
                strategy = Strategy.SingleVoiceCallStereo,
            ),
            Seed(
                offsetMs = -1 * day - 14 * 60 * 60 * 1000L,
                durSec = 48,
                name = null,
                number = "+380939876543",
                strategy = Strategy.SingleVoiceCallMono,
            ),
            // ─── Earlier this week ──────────────────────────────────────────
            Seed(
                offsetMs = -2 * day - 5 * 60 * 60 * 1000L, // 3-speaker interview
                durSec = 2148,
                name = null,
                number = null,
                modeOverride = CallMonitorService.MODE_VOICE_MEMO,
                strategy = Strategy.SingleMic,
                notes = "Interview with candidate for Backend Lead position.",
            ),
            Seed(
                offsetMs = -3 * day,
                durSec = 534, // 8:54
                name = "Taras",
                number = "+380963141592",
                strategy = Strategy.DualUplinkDownlink,
                notes = "Agreed on Thursday at 4:00 PM.",
            ),
            Seed(
                offsetMs = -4 * day,
                durSec = 1331, // 22:11
                name = "Marina (HR)",
                number = "+380957708888",
                strategy = Strategy.DualUplinkDownlink,
            ),
            // ─── Older ─────────────────────────────────────────────────────
            Seed(
                offsetMs = -7 * day,
                durSec = 540, // lecture-style voice memo with one speaker
                name = null,
                number = null,
                modeOverride = CallMonitorService.MODE_VOICE_MEMO,
                strategy = Strategy.SingleMic,
            ),
            Seed(
                offsetMs = -10 * day,
                durSec = 92,
                name = "Dentist",
                number = "+380445550099",
                strategy = Strategy.SingleMic,
            ),
            Seed(
                offsetMs = -22 * day,
                durSec = 34,
                name = "Nova Post Delivery",
                number = "+380500304400",
                strategy = Strategy.SingleMic,
            ),
        )

        for (seed in seeds) {
            val callId = UUID.randomUUID().toString()
            val started = now + seed.offsetMs
            val ended = started + seed.durSec * 1000L

            // Always write a tiny silent WAV for uplink. For dual strategies
            // also write a downlink track so MediaPlayer can prepare both.
            val uplink = File(baseDir, "$callId-uplink.wav")
            writeSilentWav(uplink)
            val downlink = if (seed.strategy.isDual) {
                File(baseDir, "$callId-downlink.wav").also { writeSilentWav(it) }
            } else null

            dao.upsert(
                CallRecord(
                    callId = callId,
                    startedAt = started,
                    endedAt = ended,
                    contactNumber = seed.number,
                    contactName = seed.name,
                    mode = seed.modeOverride ?: seed.strategy.name,
                    uplinkPath = uplink.absolutePath,
                    downlinkPath = downlink?.absolutePath,
                    notes = seed.notes,
                    favorite = seed.favorite,
                ),
            )
        }
        return seeds.size
    }

    private data class Seed(
        val offsetMs: Long,
        val durSec: Int,
        val name: String?,
        val number: String?,
        val strategy: Strategy,
        val favorite: Boolean = false,
        val notes: String? = null,
        /** Override the persisted `mode` field — e.g. [CallMonitorService.MODE_VOICE_MEMO]. */
        val modeOverride: String? = null,
    )

    /**
     * Write a 3-second mono 16-bit 16 kHz silent WAV. ~96 KB — small enough to
     * seed eight tracks with no visible storage impact, but big enough to look
     * normal in the file list and to prepare cleanly in MediaPlayer.
     */
    private fun writeSilentWav(out: File) {
        val sampleRate = 16_000
        val seconds = 3
        val numSamples = sampleRate * seconds
        val byteRate = sampleRate * 2
        val dataSize = numSamples * 2
        val totalSize = 36 + dataSize

        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)            // subchunk1 size
        buf.putShort(1)           // audio format = PCM
        buf.putShort(1)           // channels = mono
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(2)           // block align
        buf.putShort(16)          // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        // Zero-filled by allocate() — that's our silent payload.
        out.writeBytes(buf.array())
    }

    private suspend fun CallDao.observeAllOnce(): List<CallRecord> =
        observeAll().first()

    companion object {
        private const val TAG = "SeedData"
    }
}
