package com.example.llmserverapp.ui.screens.models

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.example.llmserverapp.ui.screens.ScreenTemplate

@Composable
fun ModelsScreen(navController: NavHostController) {
    ScreenTemplate(navController, "Models") {
        Text("Model selection UI goes here")
    }
}
