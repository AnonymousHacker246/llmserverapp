package com.example.llmserverapp.ui.screens.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.example.llmserverapp.ui.screens.ScreenTemplate

@Composable
fun SettingsScreen(navController: NavHostController) {
    ScreenTemplate(navController, "Settings") {
        Text("Settings UI goes here")
    }
}
