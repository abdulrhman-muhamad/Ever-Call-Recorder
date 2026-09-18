// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.coolappstore.evercallrecorder.by.svhp.core.L
import com.coolappstore.evercallrecorder.by.svhp.di.RecorderGraph
import com.coolappstore.evercallrecorder.by.svhp.storage.BulkOps
import kotlinx.coroutines.launch

/**
 * Handles the Delete action on the saved-recording notification, so a
 * recording the user never wants to keep can go without opening any UI.
 *
 * Deletion is the same two-step the playback screen does — files first, then
 * the Room row — run on the recorder's appScope because a receiver's own
 * lifetime is a few seconds at most. [goAsync] keeps the process alive until
 * the row is gone; the notification is cancelled last so a crash mid-way
 * leaves a visible reminder rather than a silent half-delete.
 *
 * Share needs no receiver: the notification launches the system chooser
 * directly through a PendingIntent (see [CompletedRecordingNotification]).
 */
class RecordingActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DELETE) return
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        val pending = goAsync()
        val container = RecorderGraph.container
        container.appScope.launch {
            try {
                val rec = container.db.calls().byId(callId)
                if (rec != null) {
                    BulkOps.deleteFiles(listOf(rec))
                    container.db.calls().delete(callId)
                    L.i(TAG, "deleted callId=$callId from notification")
                } else {
                    L.w(TAG, "delete: callId=$callId not found (already gone?)")
                }
                NotificationManagerCompat.from(context)
                    .cancel(CompletedRecordingNotification.notificationIdFor(callId))
            } catch (t: Throwable) {
                L.w(TAG, "delete from notification failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DELETE = "callrec.action.DELETE_RECORDING"
        const val EXTRA_CALL_ID = "callrec.call_id"
        private const val TAG = "RecordingAction"
    }
}
