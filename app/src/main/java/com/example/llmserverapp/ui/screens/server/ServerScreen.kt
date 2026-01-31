package com.example.llmserverapp.ui.screens.server

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.example.llmserverapp.ui.screens.ScreenTemplate

@Composable
fun ServerScreen(navController: NavHostController) {
    ScreenTemplate(navController, "Server Controls") {
        Text("Start/Stop server UI goes here")
    }
}
