// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a user-configurable file name for a [CallRecord] when it leaves the
 * app — share sheet, zip export.
 *
 * Deliberately NOT applied to the on-disk recording: [RecordingStorage.create]
 * names files at call *start*, before the post-mortem CallLog lookup has
 * resolved the number, so every template referencing a contact would render
 * "Unknown". Renaming later would also have to stay in step with the report
 * upload queue, which captures paths at finalisation. Formatting on the way
 * out gives the readable name with none of that coupling.
 *
 * Unknown placeholders are left verbatim rather than blanked, so a typo shows
 * up in the settings preview instead of silently vanishing.
 */
object RecordingFileNameFormatter {

    /** Default: sortable date first, then who the call was with. */
    const val DEFAULT_TEMPLATE = "{date}_{time}_{contact}"

    /** Placeholders offered in the settings hint, in display order. */
    val PLACEHOLDERS = listOf(
        "{date}", "{time}", "{datetime}", "{contact}", "{number}", "{callid}", "{tag}",
    )

    /**
     * Render [template] for [rec]. [tag] distinguishes the halves of a dual
     * recording ("uplink"/"downlink"); pass null for single-track files.
     *
     * The result is sanitised and always non-blank — a template that renders
     * to nothing (e.g. "{contact}" on an unknown number with the fallback
     * stripped) falls back to the callId so the share never produces a file
     * called ".m4a".
     */
    fun format(template: String, rec: CallRecord, tag: String? = null): String {
        val date = Date(rec.startedAt)
        val contact = rec.contactName?.takeIf { it.isNotBlank() }
            ?: rec.contactNumber?.takeIf { it.isNotBlank() }
            ?: UNKNOWN
        val rendered = template
            .replace("{date}", DATE.format(date))
            .replace("{time}", TIME.format(date))
            .replace("{datetime}", DATETIME.format(date))
            .replace("{contact}", contact)
            .replace("{number}", rec.contactNumber?.takeIf { it.isNotBlank() } ?: UNKNOWN)
            .replace("{callid}", rec.callId)
            .replace("{tag}", tag ?: "")
        return sanitise(rendered).ifBlank { rec.callId }
    }

    /**
     * Strip anything that is not safe in a file name on FAT/exFAT (which SD
     * cards and many share targets still use), collapse the runs that leaves
     * behind, and cap the length so the name plus extension stays inside the
     * 255-byte limit every Android filesystem enforces.
     */
    private fun sanitise(raw: String): String = raw
        .replace(ILLEGAL, "_")
        .replace(RUNS, "_")
        .trim('_', '.', ' ')
        .take(MAX_LEN)

    private const val UNKNOWN = "Unknown"
    private const val MAX_LEN = 120
    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")
    private val RUNS = Regex("_{2,}")
    private val DATE = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val TIME = SimpleDateFormat("HH-mm-ss", Locale.US)
    private val DATETIME = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
}
