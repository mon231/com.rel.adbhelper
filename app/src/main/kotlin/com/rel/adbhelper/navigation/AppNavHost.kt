package com.rel.adbhelper.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.rel.adbhelper.connect.ConnectScreen
import com.rel.adbhelper.core.session.DeviceSession
import com.rel.adbhelper.device.DeviceScreen

private const val ROUTE_CONNECT = "connect"
private const val ROUTE_DEVICE = "device"

/**
 * Hosts the app's navigation graph. Connect is the start destination; once a target is
 * connected the hub [DeviceScreen] takes over.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = ROUTE_CONNECT, modifier = modifier) {
        composable(ROUTE_CONNECT) {
            ConnectScreen(onConnected = { navController.navigate(ROUTE_DEVICE) })
        }
        composable(ROUTE_DEVICE) {
            // No session: the back stack was restored after process death (or the activity was
            // destroyed in the background), so there is nothing to show — go back to Connect.
            if (DeviceSession.client == null) {
                LaunchedEffect(Unit) { navController.popBackStack(ROUTE_CONNECT, inclusive = false) }
            } else {
                DeviceScreen(onDisconnect = { navController.popBackStack(ROUTE_CONNECT, inclusive = false) })
            }
        }
    }
}
