// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from hari161008/Ever-Call-Recorder 6c2f895/06b368f (ui/common/ScrollHaptics.kt);
// the enabled flag is passed in from DataStore instead of read from SharedPreferences.
package com.coolappstore.evercallrecorder.by.svhp.ui.components

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private const val TICK_AMPLITUDE = 60
private const val TICK_MS = 10L

/**
 * Fires a short vibration tick roughly every 1.5 cm of scroll travel in
 * [listState] — the "detent" feel of a physical wheel. Distance is tracked in
 * absolute pixels (index × first-item height + offset) so a fast fling still
 * produces one tick per threshold crossed rather than one per frame.
 *
 * Purely cosmetic and off by default (Settings → Appearance).
 */
@Composable
fun ScrollHapticsEffect(listState: LazyListState, enabled: Boolean) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val pxPerCm = with(density) { (160f / 2.54f).dp.toPx() }
    val pxThreshold = (1.5f * pxPerCm).coerceAtLeast(8f)
    val currentEnabled by rememberUpdatedState(enabled)

    LaunchedEffect(listState) {
        var lastAbsolutePx = 0f
        var bucket = 0f
        var initialized = false
        snapshotFlow {
            val info = listState.layoutInfo
            val itemSize = info.visibleItemsInfo.firstOrNull()?.size?.toFloat()?.takeIf { it > 0f }
                ?: info.viewportSize.height.toFloat().takeIf { it > 0f }
                ?: 1f
            listState.firstVisibleItemIndex * itemSize + listState.firstVisibleItemScrollOffset
        }.collect { absolutePx ->
            if (!initialized) {
                lastAbsolutePx = absolutePx
                initialized = true
                return@collect
            }
            val delta = abs(absolutePx - lastAbsolutePx)
            lastAbsolutePx = absolutePx
            if (!currentEnabled) {
                bucket = 0f
                return@collect
            }
            bucket += delta
            if (bucket >= pxThreshold) {
                val ticks = (bucket / pxThreshold).toInt()
                bucket -= ticks * pxThreshold
                tick(context)
            }
        }
    }
}

private fun tick(context: Context) {
    runCatching {
        context.getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?.vibrate(VibrationEffect.createOneShot(TICK_MS, TICK_AMPLITUDE))
    }
}
