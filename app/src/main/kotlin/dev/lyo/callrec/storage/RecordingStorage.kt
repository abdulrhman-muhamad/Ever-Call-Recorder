// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.coolappstore.evercallrecorder.by.svhp.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Names and creates on-disk targets for raw PCM dumps. Returns a
 * [RecordingFile] which the encoder layer turns into a properly-formatted
 * WAV (or AAC) file.
 *
 * Naming convention:
 *   <yyyy-MM-dd_HH-mm-ss>__<callId>__<tag>.wav
 */
class RecordingStorage(
    private val appCtx: Context,
    private val settings: AppSettings,
) {
    /**
     * Convert a SAF tree URI string (content://...) to a real File path.
     * Handles the common "primary:Folder/Sub" document ID format.
     * Returns null if the URI cannot be resolved to a writable path.
     */
    private fun safUriToFile(uriString: String): File? {
        return runCatching {
            val uri = Uri.parse(uriString)
            // Only handle content:// tree URIs
            if (uri.scheme != "content") return@runCatching File(uriString).takeIf { it.canWrite() }
            val docId = DocumentsContract.getTreeDocumentId(uri)
            // docId format: "primary:RelativePath" or "XXXX-XXXX:RelativePath" (SD card)
            if (docId.contains(":")) {
                val split = docId.split(":", limit = 2)
                val volume = split[0]
                val relativePath = split[1]
                val base: File? = when {
                    volume.equals("primary", ignoreCase = true) ->
                        Environment.getExternalStorageDirectory()
                    else -> {
                        // Removable storage — find it in /storage/
                        File("/storage/$volume").takeIf { it.exists() }
                    }
                }
                base?.resolve(relativePath)?.apply { mkdirs() }
            } else null
        }.getOrNull()
    }

    /**
     * Resolve the active recording directory. Custom path wins if set and
     * writable; otherwise fall back to the app's external files dir.
     */
    private fun resolveBaseDir(): File {
        val customUri = runBlocking {
            runCatching { settings.customRecordingPath.first() }.getOrNull()
        }
        if (!customUri.isNullOrBlank()) {
            val resolved = safUriToFile(customUri)
            if (resolved != null) {
                runCatching { resolved.mkdirs() }
                if (resolved.exists() && resolved.canWrite()) return resolved
            }
        }
        return resolveDefaultDir()
    }

    /**
     * Default path: /storage/emulated/0/Recordings/Call Recordings/
     * Falls back to app-private external dir if public storage isn't available.
     */
    private fun resolveDefaultDir(): File {
        val publicRecordings = runCatching {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RECORDINGS)
        }.getOrNull()

        val base: File = if (publicRecordings != null && (publicRecordings.exists() || publicRecordings.mkdirs())) {
            publicRecordings
        } else {
            // Fallback: internal Recordings via Music dir on older ROMs
            runCatching {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    ?.resolve("Recordings")
            }.getOrNull() ?: appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        }

        val callRecordings = base.resolve("Call Recordings")
        if (!callRecordings.exists()) callRecordings.mkdirs()
        return if (callRecordings.exists() && callRecordings.canWrite()) callRecordings
        else base.apply { mkdirs() }
    }

    fun newCallId(): String {
        val v = System.currentTimeMillis()
        val sb = StringBuilder(8)
        var r = v
        repeat(5) { sb.append(BASE32[(r and 0x1F).toInt()]); r = r ushr 5 }
        return sb.reverse().toString()
    }

    fun create(callId: String, tag: String, ext: String): RecordingFile {
        val baseDir = resolveBaseDir()
        val ts = TIMESTAMP.format(Date())
        val name = "${ts}__${callId}__${tag}"
        val file = File(baseDir, "$name.$ext")
        return RecordingFile(name = name, tag = tag, path = file.absolutePath)
    }

    fun listAll(): List<File> {
        val baseDir = resolveBaseDir()
        return baseDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    companion object {
        private const val BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}
