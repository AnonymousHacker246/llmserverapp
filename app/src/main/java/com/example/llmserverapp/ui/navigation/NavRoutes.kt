package com.example.llmserverapp.ui.navigation

sealed class NavRoute(val route: String) {
    object Home : NavRoute("home")
    object Models : NavRoute("models")
    object Server : NavRoute("server")
    object Logs : NavRoute("logs")
    object Settings : NavRoute("settings")
    object Store : NavRoute("store")
    object Account : NavRoute("account")
}
