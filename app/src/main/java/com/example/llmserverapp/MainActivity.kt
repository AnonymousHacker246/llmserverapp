package com.example.llmserverapp

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.example.llmserverapp.ui.navigation.AppNavHost
import com.example.llmserverapp.ui.navigation.MainScaffold
import com.example.llmserverapp.ui.theme.LlmServerAppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ServerController.init(this)
        window.setBackgroundDrawableResource(android.R.color.black)

        // --- Modern fullscreen API (correct placement) ---
        window.decorView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)

                window.insetsController?.apply {
                    hide(android.view.WindowInsets.Type.systemBars())
                    systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }


        // --- Compose content ---
        setContent {
            LlmServerAppTheme {
                val navController = rememberNavController()
                MainScaffold(navController) { innerModifier ->
                    AppNavHost(
                        navController = navController,
                        modifier = innerModifier
                    )
                }
            }
        }
    }
}