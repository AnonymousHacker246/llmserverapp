package com.example.llmserverapp.ui.navigation

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage

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
                    onClick = { navController.navigate(NavRoute.Home.route) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = currentRoute(navController) == NavRoute.Models.route,
                    onClick = { navController.navigate(NavRoute.Models.route) },
                    icon = { Icon(Icons.Filled.Storage, contentDescription = null) },
                    label = { Text("Models") }
                )
                NavigationBarItem(
                    selected = currentRoute(navController) == NavRoute.Server.route,
                    onClick = { navController.navigate(NavRoute.Server.route) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Server") }
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
