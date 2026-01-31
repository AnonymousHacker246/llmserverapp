package com.example.llmserverapp

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.example.llmserverapp.core.logging.LogBuffer
import com.example.llmserverapp.ui.navigation.AppNavHost
import com.example.llmserverapp.ui.navigation.MainScaffold
import com.example.llmserverapp.ui.theme.LlmServerAppTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                LogBuffer.info("Notification permission granted", tag = "PERM")
            } else {
                LogBuffer.info("Notification permission denied", tag = "PERM")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ServerController.init(this)
        window.setBackgroundDrawableResource(android.R.color.black)

        // --- Modern fullscreen API ---
        window.decorView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.apply {
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

        // --- Request notification permission (Android 13+) ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                requestNotificationsPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
