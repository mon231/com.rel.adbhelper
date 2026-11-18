package com.rel.adbhelper.connect

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Debug builds only (the release source set has empty versions of these): the Connect screen's
 * "Own stack" button and its dialog, which run the app over our own USB protocol stack on a TCP
 * socket to the address in the address field. */
@Composable
fun OwnStackButton(address: Pair<String, Int>?, onClick: (Pair<String, Int>) -> Unit) {
    TextButton(enabled = address != null, onClick = { address?.let(onClick) }) { Text("Own stack (debug)") }
}

@Composable
fun OwnStackDialog(address: Pair<String, Int>, onConnected: () -> Unit, onDismiss: () -> Unit) {
    UsbConnectDialog(
        onConnected = onConnected,
        onDismiss = onDismiss,
        title = "Own stack (debug)",
        ownStack = address,
    )
}
