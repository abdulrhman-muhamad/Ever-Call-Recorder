// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.ui.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.coolappstore.evercallrecorder.by.svhp.core.L
import com.coolappstore.evercallrecorder.by.svhp.storage.CallRecord
import java.io.File
import java.util.Locale
import com.coolappstore.evercallrecorder.by.svhp.codec.AudioMixer
import com.coolappstore.evercallrecorder.by.svhp.storage.RecordingFileNameFormatter

/**
 * Centralised share helpers for the playback screen. Three flavours:
 *
 *  - [shareSingle]:  one-track recording → one ACTION_SEND intent.
 *  - [shareSeparate]: dual-track → ACTION_SEND_MULTIPLE with both files.
 *  - [shareStereoMix]: dual-track → mix to one stereo .wav, then SEND.
 *
 * Stereo mixes are cached under `cacheDir/export/<callId>-stereo.wav` (also
 * exposed by the FileProvider's `cache-path/export` mapping). The cache is
 * invalidated by mtime: if either source file is newer than the cached mix,
 * we regenerate. This handles the rare "user re-recorded after a calibration
 * fix and reused the same callId" case.
 */
internal object Sharing {

    fun shareSingle(ctx: Context, rec: CallRecord, template: String) {
        val authority = "${ctx.packageName}.fileprovider"
        val file = named(ctx, File(rec.uplinkPath), rec, template, tag = null)
        val uri = runCatching { FileProvider.getUriForFile(ctx, authority, file) }.getOrNull()
            ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { ctx.startActivity(Intent.createChooser(intent, null)) }
    }

    fun shareSeparate(ctx: Context, rec: CallRecord, template: String) {
        val authority = "${ctx.packageName}.fileprovider"
        val files = buildList {
            // Tag the halves so the two files stay distinguishable even when
            // the template itself omits {tag}.
            add(named(ctx, File(rec.uplinkPath), rec, template, tag = "uplink"))
            rec.downlinkPath?.let {
                add(named(ctx, File(it), rec, template, tag = "downlink"))
            }
        }
        val uris = files.mapNotNull {
            runCatching { FileProvider.getUriForFile(ctx, authority, it) }.getOrNull()
        }
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(intent, null)) }
    }

    /**
     * Build (or reuse cached) stereo WAV for [rec], then fire ACTION_SEND.
     * Returns true if the share was launched, false if mixing failed.
     *
     * Heavy: must be called from a worker dispatcher.
     */
    fun shareStereoMix(ctx: Context, rec: CallRecord, template: String): Boolean {
        val mix = named(ctx, buildOrReuseStereoMix(ctx, rec) ?: return false, rec, template, tag = "stereo")
        val authority = "${ctx.packageName}.fileprovider"
        val uri = runCatching { FileProvider.getUriForFile(ctx, authority, mix) }.getOrNull()
            ?: return false
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { ctx.startActivity(Intent.createChooser(intent, null)) }
        return true
    }

    /** Returns cached mix if valid, otherwise rebuilds. Null on decode failure. */
    private fun buildOrReuseStereoMix(ctx: Context, rec: CallRecord): File? {
        val downlink = rec.downlinkPath?.let { File(it) } ?: return null
        val uplink = File(rec.uplinkPath)
        val out = File(ctx.cacheDir, "export/${rec.callId}-stereo.wav")
        if (out.exists() && out.lastModified() >= maxOf(uplink.lastModified(), downlink.lastModified())) {
            L.i(TAG, "stereo cache hit → ${out.path}")
            return out
        }
        out.parentFile?.mkdirs()
        return AudioMixer.mixToStereoWav(uplink, downlink, out)
    }

    /**
     * Build a chooser-ready ACTION_SEND / ACTION_SEND_MULTIPLE intent for [rec]
     * without launching it — used by the saved-recording notification, which
     * has to hand the OS a PendingIntent rather than call startActivity itself.
     * Files go through [named] so the shared name follows the user's template,
     * and — because the named copy lives in cacheDir — a recording stored in a
     * user-picked SAF folder outside the FileProvider paths still shares.
     * Returns null when no file could be exposed via FileProvider.
     */
    fun shareIntent(ctx: Context, rec: CallRecord, template: String): Intent? {
        val authority = "${ctx.packageName}.fileprovider"
        val files = buildList {
            val down = rec.downlinkPath
            if (down == null) {
                add(named(ctx, File(rec.uplinkPath), rec, template, tag = null))
            } else {
                add(named(ctx, File(rec.uplinkPath), rec, template, tag = "uplink"))
                add(named(ctx, File(down), rec, template, tag = "downlink"))
            }
        }
        val uris = files.mapNotNull {
            runCatching { FileProvider.getUriForFile(ctx, authority, it) }.getOrNull()
        }
        if (uris.isEmpty()) return null
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "audio/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        return intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Return a copy of [src] under `cacheDir/export/` named per [template].
     *
     * A copy is unavoidable: the share sheet and every receiving app take the
     * display name from the file itself, so the only way to hand over a
     * readable name is to hand over a differently-named file. The copy is
     * skipped when a valid one already exists (mtime >= source), and lands in
     * cacheDir so the OS can reclaim it under storage pressure.
     *
     * On any failure — no space, unwritable cache — we fall back to sharing
     * the original file. A worse name beats a failed share.
     */
    private fun named(
        ctx: Context,
        src: File,
        rec: CallRecord,
        template: String,
        tag: String?,
    ): File = runCatching {
        val ext = src.extension
        val base = RecordingFileNameFormatter.format(template, rec, tag)
        val out = File(ctx.cacheDir, "export/$base${if (ext.isBlank()) "" else ".$ext"}")
        if (out.path == src.path) return src
        if (out.exists() && out.lastModified() >= src.lastModified()) return out
        out.parentFile?.mkdirs()
        src.copyTo(out, overwrite = true)
        L.i(TAG, "export copy → ${out.name}")
        out
    }.getOrElse {
        L.w(TAG, "export rename failed, sharing original: ${it.message}")
        src
    }

    private fun mimeFor(f: File): String = when (f.extension.lowercase(Locale.US)) {
        "wav" -> "audio/wav"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        else -> "audio/*"
    }

    private const val TAG = "Sharing"
}
