package com.example.llmserverapp.ui.navigation

import com.example.llmserverapp.ui.screens.ServerScreen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.llmserverapp.ui.screens.account.AccountScreen
import com.example.llmserverapp.ui.screens.home.HomeScreen
import com.example.llmserverapp.ui.screens.logs.LogsScreen
import com.example.llmserverapp.ui.screens.models.ModelsScreen
import com.example.llmserverapp.ui.screens.settings.SettingsScreen
import com.example.llmserverapp.ui.screens.store.StoreScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = NavRoute.Home.route,
        modifier = modifier
    ) {
        composable(NavRoute.Home.route) {
            HomeScreen(navController)
        }
        composable(NavRoute.Models.route) {
            ModelsScreen()
        }
        composable(NavRoute.Server.route) {
            ServerScreen()
        }
        composable(NavRoute.Logs.route) {
            LogsScreen()
        }
        composable(NavRoute.Settings.route) {
            SettingsScreen(navController)
        }
        composable(NavRoute.Store.route) {
            StoreScreen(navController)
        }
        composable(NavRoute.Account.route) {
            AccountScreen(navController)
        }
    }
}
