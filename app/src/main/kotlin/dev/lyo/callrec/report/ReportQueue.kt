// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.report

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.telephony.PhoneNumberUtils
import com.coolappstore.evercallrecorder.by.svhp.core.L
import com.coolappstore.evercallrecorder.by.svhp.di.RecorderGraph
import com.coolappstore.evercallrecorder.by.svhp.storage.PendingReport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Durable offline queue for server reports. Adapts the `call_recording-main`
 * RemoteSyncHelper pattern: every report is persisted as a [PendingReport] row,
 * a drain pass POSTs each row, deletes it on a 2xx, and leaves failures in the
 * DB to retry on the next trigger (enqueue, connectivity-returns, app start).
 */
object ReportQueue {

    private const val TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    private const val MAX_ATTEMPTS = 100 // runaway guard; TTL is the real cap

    private val drainMutex = Mutex()
    private val container get() = RecorderGraph.container

    suspend fun enqueueMeta(
        ctx: Context,
        number: String?,
        incoming: Boolean,
        startMs: Long,
        durationSec: Long,
        ringSec: Long,
    ) {
        // The webhook requires a number; skip calls with no caller ID.
        val cleaned = number?.let { PhoneNumberUtils.stripSeparators(it.removePrefix("tel:")) }
        if (cleaned.isNullOrBlank()) return
        container.db.pendingReports().insert(
            PendingReport(
                kind = PendingReport.KIND_META,
                callId = null,
                number = number,
                incoming = incoming,
                dateMs = startMs,
                durationSec = durationSec,
                ringSec = ringSec,
                createdAt = System.currentTimeMillis(),
            ),
        )
        triggerDrain(ctx)
    }

    suspend fun enqueueFile(
        ctx: Context,
        callId: String,
        number: String?,
        incoming: Boolean,
        dateMs: Long,
    ) {
        container.db.pendingReports().insert(
            PendingReport(
                kind = PendingReport.KIND_FILE,
                callId = callId,
                number = number,
                incoming = incoming,
                dateMs = dateMs,
                durationSec = 0,
                ringSec = 0,
                createdAt = System.currentTimeMillis(),
            ),
        )
        triggerDrain(ctx)
    }

    /** Fire-and-forget drain on the app scope. */
    fun triggerDrain(ctx: Context) {
        val appCtx = ctx.applicationContext
        container.appScope.launch { drainOnce(appCtx) }
    }

    /** Send every queued row; delete on success, keep+count on failure. */
    suspend fun drainOnce(ctx: Context) = drainMutex.withLock {
        val dao = container.db.pendingReports()
        runCatching { dao.deleteOlderThan(System.currentTimeMillis() - TTL_MS) }
        val settings = container.settings
        if (!settings.reportingEnabled.first()) return@withLock
        if (settings.reportUrl.first().isBlank() || settings.reportSecret.first().isBlank()) return@withLock

        val rows = runCatching { dao.all() }.getOrElse { return@withLock }
        if (rows.isEmpty()) return@withLock
        L.i("Report", "draining ${rows.size} queued report(s)")
        for (row in rows) {
            if (row.attempts >= MAX_ATTEMPTS) {
                runCatching { dao.delete(row.id) }
                continue
            }
            val ok = runCatching { trySend(ctx, row) }.getOrDefault(false)
            runCatching { if (ok) dao.delete(row.id) else dao.bumpAttempts(row.id) }
            if (!ok) {
                // Likely offline / server down — stop hammering; the connectivity
                // callback (or next enqueue) will trigger another pass.
                break
            }
        }
    }

    private suspend fun trySend(ctx: Context, row: PendingReport): Boolean = when (row.kind) {
        PendingReport.KIND_META ->
            CallReporter.sendCallLog(ctx, row.number, row.incoming, row.dateMs, row.durationSec, row.ringSec)
        PendingReport.KIND_FILE -> {
            val rec = row.callId?.let { container.db.calls().byId(it) }
            if (rec == null) true else CallReporter.sendRecording(ctx, rec, row.incoming, row.dateMs)
        }
        else -> true
    }
}

/**
 * Registers a default-network callback so the queue drains as soon as the
 * device regains connectivity, plus a one-shot drain at app start to flush
 * anything left over from a previous (offline) session.
 */
object ReportSync {

    @Volatile private var started = false

    fun start(appContext: Context) {
        if (started) return
        started = true
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            runCatching {
                cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        ReportQueue.triggerDrain(appContext)
                    }
                })
            }.onFailure { L.w("Report", "network callback registration failed: ${it.message}") }
        }
        ReportQueue.triggerDrain(appContext)
    }
}
