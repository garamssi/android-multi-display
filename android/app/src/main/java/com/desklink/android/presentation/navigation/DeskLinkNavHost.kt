package com.desklink.android.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.desklink.android.presentation.connection.ConnectionScreen
import com.desklink.android.presentation.display.DisplayScreen
import com.desklink.android.presentation.settings.SettingsScreen

@Composable
fun DeskLinkNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Route.CONNECTION) {
        composable(Route.CONNECTION) {
            ConnectionScreen(
                onConnected = { navController.navigate(Route.DISPLAY) },
                onSettings = { navController.navigate(Route.SETTINGS) },
            )
        }
        composable(Route.DISPLAY) {
            DisplayScreen(
                onDisconnected = {
                    navController.navigate(Route.CONNECTION) {
                        popUpTo(Route.CONNECTION) { inclusive = true }
                    }
                },
                onOpenSettings = {
                    navController.navigate(Route.SETTINGS) {
                        popUpTo(Route.CONNECTION) { inclusive = false }
                    }
                },
            )
        }
        composable(Route.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
