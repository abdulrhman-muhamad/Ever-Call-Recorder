// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.storage

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A queued report awaiting delivery to the user's server. Persisted so a POST
 * that fails (offline / server down) survives process death and is retried on
 * the next drain. Mirrors the durable-queue idea from `call_recording-main`'s
 * RemoteSyncHelper (un-synced rows stay in the DB and are re-sent next pass).
 *
 * One row == one HTTP POST. [KIND_META] is the form-urlencoded call-log; the
 * server matches a [KIND_FILE] audio upload to it by date + number, so the two
 * are independent and can deliver in any order.
 */
@Entity(tableName = "pending_reports")
data class PendingReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo("kind") val kind: String,
    /** For [KIND_FILE]: the recording to resolve + upload at send time. */
    @ColumnInfo("call_id") val callId: String?,
    @ColumnInfo("number") val number: String?,
    @ColumnInfo("incoming") val incoming: Boolean,
    @ColumnInfo("date_ms") val dateMs: Long,
    @ColumnInfo("duration_sec") val durationSec: Long,
    @ColumnInfo("ring_sec") val ringSec: Long,
    @ColumnInfo("created_at") val createdAt: Long,
    @ColumnInfo("attempts") val attempts: Int = 0,
) {
    companion object {
        const val KIND_META = "META"
        const val KIND_FILE = "FILE"
    }
}

@Dao
interface PendingReportDao {
    @Insert
    suspend fun insert(row: PendingReport): Long

    @Query("SELECT * FROM pending_reports ORDER BY created_at ASC")
    suspend fun all(): List<PendingReport>

    @Query("DELETE FROM pending_reports WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE pending_reports SET attempts = attempts + 1 WHERE id = :id")
    suspend fun bumpAttempts(id: Long)

    @Query("DELETE FROM pending_reports WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM pending_reports")
    suspend fun count(): Int
}
