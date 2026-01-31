package com.example.llmserverapp.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

@Composable
fun MainScaffold(
    navController: NavHostController,
    content: @Composable (Modifier) -> Unit
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute(navController) == NavRoute.Home.route,
                    onClick = {
                        navController.navigate(NavRoute.Home.route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                        }
                    },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = currentRoute(navController) == NavRoute.Models.route,
                    onClick = {
                        navController.navigate(NavRoute.Models.route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                        }
                    },
                    icon = { Icon(Icons.Filled.Storage, contentDescription = null) },
                    label = { Text("Models") }
                )
                NavigationBarItem(
                    selected = currentRoute(navController) == NavRoute.Server.route,
                    onClick = {
                        navController.navigate(NavRoute.Server.route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                        }
                    },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Server") }
                )
                NavigationBarItem(
                    selected = currentRoute(navController) == NavRoute.Logs.route,
                    onClick = {
                        navController.navigate(NavRoute.Logs.route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                        }
                    },
                    icon = { Icon(Icons.Filled.Terminal, contentDescription = "Logs") },
                    label = { Text("Logs") }
                )

            }
        }
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

@Composable
fun currentRoute(navController: NavHostController): String? {
    val entry = navController.currentBackStackEntryAsState()
    return entry.value?.destination?.route
}
