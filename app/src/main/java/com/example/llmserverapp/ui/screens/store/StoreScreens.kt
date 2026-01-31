package com.example.llmserverapp.ui.screens.store

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.example.llmserverapp.ui.screens.ScreenTemplate

@Composable
fun StoreScreen(navController: NavHostController) {
    ScreenTemplate(navController, "Store") {
        Text("Store / Products UI goes here")
    }
}
