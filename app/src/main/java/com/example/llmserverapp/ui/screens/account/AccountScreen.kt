package com.example.llmserverapp.ui.screens.account

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.example.llmserverapp.ui.screens.ScreenTemplate

@Composable
fun AccountScreen(navController: NavHostController) {
    ScreenTemplate(navController, "Account") {
        Text("Account / Monetization UI goes here")
    }
}
