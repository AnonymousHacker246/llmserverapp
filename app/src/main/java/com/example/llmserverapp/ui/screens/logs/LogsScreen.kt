package com.example.llmserverapp.ui.screens.logs

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.example.llmserverapp.ui.screens.ScreenTemplate

@Composable
fun LogsScreen(navController: NavHostController) {
    ScreenTemplate(navController, "Logs") {
        Text("Logs viewer goes here")
    }
}
