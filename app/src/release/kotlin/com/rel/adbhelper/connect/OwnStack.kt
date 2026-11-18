package com.rel.adbhelper.connect

import androidx.compose.runtime.Composable

/** Release builds have no "Own stack" button; the debug source set has the real ones. */
@Composable
fun OwnStackButton(address: Pair<String, Int>?, onClick: (Pair<String, Int>) -> Unit) = Unit

@Composable
fun OwnStackDialog(address: Pair<String, Int>, onConnected: () -> Unit, onDismiss: () -> Unit) = Unit
