// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from ShizuCallRecorder (kitsumed) via hari161008/Ever-Call-Recorder 89ae527..f4b6164,
// adapted to this app's DataStore settings and navigation.
package com.coolappstore.evercallrecorder.by.svhp.ui.lock

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.coolappstore.evercallrecorder.by.svhp.settings.AppLockMethod

/**
 * Full-screen "prove it's you" check, reusing the exact same PIN/password/biometric input UI as
 * the real [com.coolappstore.evercallrecorder.by.svhp.ui.screens.AppLockScreen] gate. Shown from
 * Settings before disabling App Lock or switching to a different unlock method, so a stranger
 * holding an unlocked phone can't casually turn off the protection.
 *
 * @param method         The currently configured unlock method.
 * @param onVerifySecret For PIN/password, called with what was typed; return whether it matched.
 * @param onVerified     Called once the user proved their identity.
 * @param onDismiss      Called if the user cancels out of the check.
 */
@Composable
fun AppLockVerifyDialog(
    method: AppLockMethod,
    onVerifySecret: (String) -> Boolean,
    onVerified: () -> Unit,
    onDismiss: () -> Unit
) {
    var hasAppeared by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { hasAppeared = 1f }
    val entrance by animateFloatAsState(
        targetValue = hasAppeared,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "appLockVerifyEntrance"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = method != AppLockMethod.BIOMETRIC
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        alpha = entrance,
                        scaleX = 0.94f + entrance * 0.06f,
                        scaleY = 0.94f + entrance * 0.06f
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 8.dp)) {
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                    }
                }
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (method) {
                        AppLockMethod.PIN       -> PinUnlockContent(onVerifySecret, onVerified)
                        AppLockMethod.PASSWORD  -> PasswordUnlockContent(onVerifySecret, onVerified)
                        AppLockMethod.BIOMETRIC -> BiometricUnlockContent(onVerified)
                        AppLockMethod.NONE      -> {}
                    }
                }
            }
        }
    }
}
