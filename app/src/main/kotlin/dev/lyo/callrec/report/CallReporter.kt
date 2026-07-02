// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.report

import android.content.Context
import android.telephony.PhoneNumberUtils
import com.coolappstore.evercallrecorder.by.svhp.codec.AudioMixer
import com.coolappstore.evercallrecorder.by.svhp.contacts.ContactResolver
import com.coolappstore.evercallrecorder.by.svhp.core.L
import com.coolappstore.evercallrecorder.by.svhp.di.RecorderGraph
import com.coolappstore.evercallrecorder.by.svhp.settings.AutoRecordScope
import com.coolappstore.evercallrecorder.by.svhp.storage.CallRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Posts a call-log entry to the user's server after each call, matching the
 * `acr-calls/acr-webhook` contract (ACR-Phone-compatible): a form-urlencoded
 * body authenticated by a per-user `secret`, so the server knows which user
 * the call belongs to.
 *
 * Audio upload is intentionally NOT done here: the recorder stores separate
 * uplink/downlink files, so uploading a single playable recording needs a
 * mixdown step that doesn't exist yet.
 */
object CallReporter {

    /** Honour the user's reporting filters (enabled, credentials, SIM, scope). */
    suspend fun shouldReport(ctx: Context, number: String?, callSimId: String?): Boolean {
        val settings = RecorderGraph.container.settings
        if (!settings.reportingEnabled.first()) return false
        if (settings.reportUrl.first().isBlank() || settings.reportSecret.first().isBlank()) return false

        val selectedSim = settings.reportSimId.first()
        if (!selectedSim.isNullOrBlank() && !callSimId.isNullOrBlank() && callSimId != selectedSim) {
            return false
        }

        val normalized = number?.let { normalize(it) }?.takeIf { it.isNotBlank() }
        return when (settings.reportScope.first()) {
            AutoRecordScope.ALL -> true
            AutoRecordScope.CONTACTS -> normalized != null && isContact(ctx, number!!)
            AutoRecordScope.NON_CONTACTS -> normalized != null && !isContact(ctx, number!!)
            AutoRecordScope.UNKNOWN -> normalized == null
        }
    }

    /** Result of a credentials check against the configured webhook. */
    sealed interface TestOutcome {
        object MissingConfig : TestOutcome
        object Ok : TestOutcome
        object BadSecret : TestOutcome
        data class Failed(val code: Int) : TestOutcome
        data class Error(val message: String) : TestOutcome
    }

    /**
     * Verify the configured URL + secret without recording anything. Posts the
     * webhook's test sentinel (number `0000000000`): the server validates the
     * secret first, so a bad secret returns 401 while a valid one returns 2xx —
     * letting the user confirm a pasted secret is correct.
     */
    suspend fun testConnection(): TestOutcome = withContext(Dispatchers.IO) {
        val settings = RecorderGraph.container.settings
        val endpoint = settings.reportUrl.first().trim()
        val secret = settings.reportSecret.first()
        if (endpoint.isBlank() || secret.isBlank()) return@withContext TestOutcome.MissingConfig
        val code = runCatching {
            postForm(
                endpoint,
                mapOf(
                    "secret" to secret,
                    "number" to "0000000000",
                    "date" to (System.currentTimeMillis() / 1000).toString(),
                    "duration" to "0",
                ),
            )
        }.getOrElse { return@withContext TestOutcome.Error(it.message ?: "network error") }
        when {
            code in 200..299 -> TestOutcome.Ok
            code == 401 -> TestOutcome.BadSecret
            else -> TestOutcome.Failed(code)
        }
    }

    /**
     * POST the call-log metadata. The webhook requires a number, so calls with
     * no caller ID are skipped (the server would reject them with 400).
     */
    suspend fun sendCallLog(
        ctx: Context,
        number: String?,
        incoming: Boolean,
        startMs: Long,
        durationSec: Long,
        ringSec: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanedNumber = number?.let { normalize(it) }?.takeIf { it.isNotBlank() }
            ?: return@withContext true // no number → nothing the server can accept; treat as done
        val settings = RecorderGraph.container.settings
        val endpoint = settings.reportUrl.first().trim()
        val secret = settings.reportSecret.first()
        if (endpoint.isBlank() || secret.isBlank()) return@withContext false
        val params = mapOf(
            "secret" to secret,
            "number" to cleanedNumber,
            "direction" to if (incoming) "1" else "0",
            "date" to (startMs / 1000).toString(),
            "duration" to durationSec.coerceAtLeast(0).toString(),
            "notes" to if (durationSec <= 0 && ringSec > 0) "${ringSec}s" else "",
        )
        val code = runCatching { postForm(endpoint, params) }
            .getOrElse { L.w("Report", "call-log POST failed: ${it.message}"); return@withContext false }
        (code in 200..299).also { L.i("Report", "call-log POST http=$code ok=$it") }
    }

    /**
     * Upload the recording audio so the server can attach it to the call-log
     * row. The server matches by date + number, so [callDateMs] MUST be the same
     * call-start timestamp used for the metadata post. Dual-channel recordings
     * are mixed to one stereo AAC first. Returns true on a 2xx.
     */
    suspend fun sendRecording(
        ctx: Context,
        rec: CallRecord,
        incoming: Boolean,
        callDateMs: Long?,
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanedNumber = rec.contactNumber?.let { normalize(it) }?.takeIf { it.isNotBlank() }
            ?: return@withContext true
        val file = resolveUploadFile(ctx, rec)
            ?: return@withContext true // recording gone — nothing to retry
        val settings = RecorderGraph.container.settings
        val endpoint = settings.reportUrl.first().trim()
        val secret = settings.reportSecret.first()
        if (endpoint.isBlank() || secret.isBlank()) return@withContext false
        val dateSec = (callDateMs ?: rec.startedAt) / 1000
        val durationMs = ((rec.endedAt ?: System.currentTimeMillis()) - rec.startedAt).coerceAtLeast(0)
        val dir = if (incoming) "Incoming" else "Outgoing"
        val filename = "$cleanedNumber (+$cleanedNumber) [$dateSec] [$dir].${file.extension.ifEmpty { "m4a" }}"
        val code = runCatching {
            postMultipart(endpoint, secret, file, filename, mimeFor(file), dateSec, durationMs)
        }.getOrElse { L.w("Report", "audio POST failed: ${it.message}"); return@withContext false }
        (code in 200..299).also { L.i("Report", "audio POST http=$code (${file.length() / 1024}KB) ok=$it") }
    }

    /**
     * Single recording → its file; dual recording → a cached stereo AAC (.m4a)
     * mix (≈8× smaller than WAV for the upload), falling back to WAV if the
     * AAC encoder is unavailable.
     */
    private fun resolveUploadFile(ctx: Context, rec: CallRecord): File? {
        val downlink = rec.downlinkPath?.let { File(it) }
        val uplink = File(rec.uplinkPath)
        if (downlink == null || !downlink.exists()) {
            if (!uplink.exists()) return null
            // Already-compressed single recordings (.m4a/.aac) upload as-is;
            // only a lossless WAV is worth transcoding to a smaller AAC first.
            if (uplink.extension.lowercase() != "wav") return uplink
            val out = File(ctx.cacheDir, "report/${rec.callId}-single.m4a")
            if (out.exists() && out.lastModified() >= uplink.lastModified()) return out
            out.parentFile?.mkdirs()
            return AudioMixer.transcodeToAac(uplink, out) ?: uplink
        }
        val newest = maxOf(uplink.lastModified(), downlink.lastModified())
        val aac = File(ctx.cacheDir, "report/${rec.callId}-mix.m4a")
        if (aac.exists() && aac.lastModified() >= newest) return aac
        aac.parentFile?.mkdirs()
        AudioMixer.mixToStereoAac(uplink, downlink, aac)?.let { return it }

        // AAC encode failed — fall back to an uncompressed WAV mix.
        val wav = File(ctx.cacheDir, "report/${rec.callId}-mix.wav")
        if (wav.exists() && wav.lastModified() >= newest) return wav
        return AudioMixer.mixToStereoWav(uplink, downlink, wav) ?: uplink.takeIf { it.exists() }
    }

    private fun mimeFor(f: File): String = when (f.extension.lowercase()) {
        "wav" -> "audio/wav"
        "m4a", "mp4", "aac" -> "audio/mp4"
        else -> "application/octet-stream"
    }

    private fun postMultipart(
        endpoint: String,
        secret: String,
        file: File,
        filename: String,
        fileMime: String,
        dateSec: Long,
        durationMs: Long,
    ): Int {
        val boundary = "----callrec${UUID.randomUUID()}"
        val crlf = "\r\n"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setChunkedStreamingMode(0)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write(("--$boundary$crlf").toByteArray())
                    out.write(("Content-Disposition: form-data; name=\"$name\"$crlf$crlf").toByteArray())
                    out.write((value + crlf).toByteArray())
                }
                field("secret", secret)
                field("date", dateSec.toString())
                field("duration", durationMs.toString())
                out.write(("--$boundary$crlf").toByteArray())
                out.write(
                    ("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"$crlf").toByteArray(),
                )
                out.write(("Content-Type: $fileMime$crlf$crlf").toByteArray())
                file.inputStream().use { it.copyTo(out) }
                out.write(crlf.toByteArray())
                out.write(("--$boundary--$crlf").toByteArray())
            }
            conn.responseCode.also {
                runCatching {
                    (if (it in 200..299) conn.inputStream else conn.errorStream)?.use { s -> s.readBytes() }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun postForm(endpoint: String, params: Map<String, String>): Int {
        val body = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setFixedLengthStreamingMode(body.size)
        }
        return try {
            conn.outputStream.use { out: OutputStream -> out.write(body) }
            conn.responseCode.also {
                // Drain the stream so the connection can be pooled/closed cleanly.
                runCatching {
                    (if (it in 200..299) conn.inputStream else conn.errorStream)?.use { s -> s.readBytes() }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun isContact(ctx: Context, number: String): Boolean =
        ContactResolver.resolveName(ctx, number) != null

    private fun normalize(raw: String): String =
        PhoneNumberUtils.stripSeparators(raw.removePrefix("tel:")) ?: raw
}
