package com.example.llmserverapp.ui.screens.home

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.example.llmserverapp.ui.screens.ScreenTemplate

@Composable
fun HomeScreen(navController: NavHostController) {
    ScreenTemplate(navController, "Home") {
        Text("Welcome to the Local LLM Server")
    }
}
