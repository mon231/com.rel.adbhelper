package com.rel.adbhelper

import android.view.View
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Instrumented UI tests; no sleeps, everything waits on a condition. See docs/contributing.md. */
@RunWith(AndroidJUnit4::class)
class AppUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val tabs = listOf("Info", "Apps", "Files", "Shell", "Processes", "Logs", "Power", "Monitor", "Mirror")

    @Test
    fun connectScreenShowsItsEntryPoints() {
        rule.onNode(hasSetTextAction()).assertIsDisplayed() // the address field
        listOf("Connect", "Scan", "Pair", "USB-OTG").forEach { rule.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test
    fun layoutStaysLeftToRightUnderRtlLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("he"))
            // Make the window RTL as an RTL locale would; the app's own LTR override must win,
            // so the button row keeps its left-to-right order.
            rule.activityRule.scenario.onActivity { it.window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL }
            rule.waitForIdle()
            val x = listOf("Scan", "Pair", "USB-OTG").map { rule.onNodeWithText(it).getUnclippedBoundsInRoot().left }
            assertTrue("buttons are mirrored: $x", x[0] < x[1] && x[1] < x[2])
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun usbDialogWithoutDeviceCanBeCancelled() {
        rule.onNodeWithText("USB-OTG").performClick()
        rule.waitUntil(10_000) { noUsbText().isNotEmpty() }
        rule.onNodeWithText("Cancel").performClick()
        rule.waitUntil(10_000) { noUsbText().isEmpty() }
    }

    @Test
    fun unreachableAddressEndsInAnError() {
        rule.onNode(hasSetTextAction()).performTextInput("127.0.0.1:1")
        rule.onNodeWithText("Connect").performClick()
        rule.waitUntil(60_000) { status().let { it.isNotEmpty() && it != "Connecting…" } }
        assertTrue("expected an error, got '${status()}'", !status().startsWith("Connected"))
        rule.onNodeWithText("Connect").assertIsDisplayed() // still on the Connect screen
    }

    /**
     * Needs `-Pandroid.testInstrumentationRunnerArguments.adbTarget=host:port` (the device's own
     * adbd, which must accept this app's key; see docs/contributing.md). Skipped otherwise.
     */
    @Test
    fun everyHubTabWorksAgainstOwnAdbd() {
        val target = InstrumentationRegistry.getArguments().getString("adbTarget")
        assumeTrue("pass adbTarget=host:port to run", !target.isNullOrBlank())
        rule.onNode(hasSetTextAction()).performTextInput(target!!)
        rule.onNodeWithText("Connect").performClick()
        // Generous: the first connection may wait for the key to be authorised.
        rule.waitUntil(120_000) { rule.onAllNodesWithText("Info").fetchSemanticsNodes().isNotEmpty() }
        tabs.forEach { title ->
            rule.onNode(hasText(title) and isSelectable()).performClick()
            rule.waitUntil(10_000) { runCatching { rule.onNode(hasText(title) and isSelectable()).assertIsSelected() }.isSuccess }
        }
        rule.onNode(hasText("Shell") and isSelectable()).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("echo hi")
        // "Run" only enables once the terminal session is connected.
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Run") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Run").performClick()
        rule.waitUntil(30_000) { rule.onAllNodesWithText("hi").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun noUsbText() = rule.onAllNodesWithText("No USB device", substring = true).fetchSemanticsNodes()

    private fun status(): String =
        rule.onNodeWithTag("connectStatus").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
}
