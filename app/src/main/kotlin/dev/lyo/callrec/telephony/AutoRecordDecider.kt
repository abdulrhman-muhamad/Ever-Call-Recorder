// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.telephony

import android.content.Context
import android.telephony.PhoneNumberUtils
import com.coolappstore.evercallrecorder.by.svhp.contacts.ContactResolver
import com.coolappstore.evercallrecorder.by.svhp.di.RecorderGraph
import com.coolappstore.evercallrecorder.by.svhp.settings.AutoRecordScope
import kotlinx.coroutines.flow.first

/**
 * Single source of truth for "should this call be auto-recorded?". Reads the
 * user's auto-record settings and resolves contact status as needed.
 *
 * Precedence (highest first):
 *   1. Exclude list  → never record (hard block).
 *   2. Include list  → always record (overrides SIM + scope).
 *   3. SIM filter     → skip calls on a non-selected SIM.
 *   4. Scope          → ALL / CONTACTS / NON_CONTACTS / UNKNOWN.
 * The master [autoRecord] toggle gates everything: when off, nothing
 * auto-records (the in-call Record button still works, except excluded numbers).
 */
object AutoRecordDecider {

    suspend fun shouldAutoRecord(ctx: Context, number: String?, callSimId: String?): Boolean {
        val settings = RecorderGraph.container.settings
        if (!settings.autoRecord.first()) return false

        val normalized = number?.let { normalize(it) }?.takeIf { it.isNotBlank() }

        if (normalized != null && matches(settings.excludeNumbers.first(), normalized)) return false
        if (normalized != null && matches(settings.includeNumbers.first(), normalized)) return true

        val selectedSim = settings.autoRecordSimId.first()
        if (!selectedSim.isNullOrBlank() && !callSimId.isNullOrBlank() && callSimId != selectedSim) {
            return false
        }

        return when (settings.autoRecordScope.first()) {
            AutoRecordScope.ALL -> true
            AutoRecordScope.CONTACTS -> normalized != null && isContact(ctx, number!!)
            AutoRecordScope.NON_CONTACTS -> normalized != null && !isContact(ctx, number!!)
            AutoRecordScope.UNKNOWN -> normalized == null
        }
    }

    /** True if [number] is on the exclude list — a hard "never record" rule. */
    suspend fun isExcluded(number: String?): Boolean {
        val normalized = number?.let { normalize(it) }?.takeIf { it.isNotBlank() } ?: return false
        return matches(RecorderGraph.container.settings.excludeNumbers.first(), normalized)
    }

    private suspend fun isContact(ctx: Context, number: String): Boolean =
        ContactResolver.resolveName(ctx, number) != null

    private fun matches(set: Set<String>, normalized: String): Boolean =
        set.any { stored ->
            val s = normalize(stored)
            s == normalized || PhoneNumberUtils.compare(s, normalized)
        }

    private fun normalize(raw: String): String =
        PhoneNumberUtils.stripSeparators(raw.removePrefix("tel:")) ?: raw
}
