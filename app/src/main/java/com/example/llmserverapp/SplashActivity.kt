package com.example.llmserverapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.setContent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.llmserverapp.NetworkUtils.hasInternet

class SplashActivity : ComponentActivity() {

    private var recieverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {

                "MODEL_DOWNLOAD_PROGRESS" -> {
                    val p = intent.getIntExtra("progress", 0)
                    SplashState.progress.value = p
                }

                "MODEL_DOWNLOAD_STATUS" -> {
                    val status = intent.getStringExtra("status") ?: "Preparing…"
                    SplashState.status.value = status
                }

                "MODEL_DOWNLOAD_FINISHED" -> {
                    startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!hasInternet(this)) {
            println("SPLASH: no internet, launching MainActivity")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val filter = IntentFilter().apply {
            addAction("MODEL_DOWNLOAD_PROGRESS")
            addAction("MODEL_DOWNLOAD_STATUS")
            addAction("MODEL_DOWNLOAD_FINISHED")
        }
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        recieverRegistered = true
        startForegroundService(Intent(this, ModelDownloadService::class.java))

        setContent {
            FancySplashScreenWrapper()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (recieverRegistered){
            unregisterReceiver(receiver)
        }
    }
}

@Composable
fun FancySplashScreen(
    progress: Int,
    status: String
) {
    // Hacker colors
    val neonGreen = Color(0xFF00FF88)
    val darkBg = Color(0xFF000000)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBg)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {

        // --- Scanline overlay ---
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = 0.08f }
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White,
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = 12f
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // --- Pulsing neon glow behind icon ---

            val infinite = rememberInfiniteTransition()
            val glow by infinite.animateFloat(
                initialValue = 0.7f,
                targetValue = 1.3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )

            val rotation by infinite.animateFloat(
                initialValue = -2f,
                targetValue = 2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )

            val verticalPulse by infinite.animateFloat(
                initialValue = -4f,
                targetValue = 4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )

            Box(
                modifier = Modifier
                    .size(140.dp)
                    .graphicsLayer {
                        scaleX = glow
                        scaleY = glow
                        rotationZ = rotation
                        translationY = verticalPulse
                        alpha = 0.3f
                    }
                    .background(neonGreen, shape = CircleShape)
            )

            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = neonGreen,
                modifier = Modifier
                    .size(72.dp)
                    .offset(y = (-90).dp)
                    .graphicsLayer {
                        rotationZ = rotation
                        translationY = verticalPulse
                    }
            )


            Spacer(Modifier.height(16.dp))

            // --- Hacker progress bar ---
            LinearProgressIndicator(
                progress = { progress / 100f },
                color = neonGreen,
                trackColor = Color(0xFF003322),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Spacer(Modifier.height(16.dp))

            // --- Terminal-style status text ---
            Text(
                text = status,
                color = neonGreen,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(8.dp))

            // --- Terminal-style percent ---
            Text(
                text = "$progress%",
                color = neonGreen,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun FancySplashScreenWrapper() {
    val progress by SplashState.progress
    val status by SplashState.status

    FancySplashScreen(
        progress = progress,
        status = status
    )
}

object SplashState {
    val progress = mutableStateOf(0)
    val status = mutableStateOf("Preparing…")
}
