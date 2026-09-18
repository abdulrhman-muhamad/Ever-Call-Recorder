// SPDX-License-Identifier: GPL-3.0-or-later
package com.coolappstore.evercallrecorder.by.svhp.ui.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide "has the user unlocked this session" flag. Lives in the DI
 * container rather than a ViewModel so it survives Activity recreation
 * (rotation) but not process death — a fresh process always starts locked,
 * which is the safe default.
 *
 * Whether the gate is *shown* is decided by the caller from
 * [com.coolappstore.evercallrecorder.by.svhp.settings.AppSettings.appLockState]:
 * enabled && !unlocked. So a disabled lock never consults this flag at all.
 */
class AppLockGate {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun unlock() { _unlocked.value = true }

    /** Called when the app leaves the foreground; the next launch re-prompts. */
    fun lock() { _unlocked.value = false }
}
