package com.example.llmserverapp.ui.screens.server

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llmserverapp.ServerController
import com.example.llmserverapp.core.models.ModelManager
import com.example.llmserverapp.core.models.ModelStatus

@Composable
fun ServerScreen() {
    val isRunning by ServerController.isRunning.collectAsState()
    val models by ModelManager.models.collectAsState()

    val loaded = models.firstOrNull { it.status == ModelStatus.Loaded }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        Text(
            "Server",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(16.dp))

        // Show loaded model
        Text(
            text = "Loaded model: ${loaded?.prettyName ?: "None"}",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(Modifier.height(16.dp))

        Row {
            Button(
                onClick = { ServerController.startServer() },
                enabled = loaded != null && !isRunning
            ) {
                Text("Start Server")
            }

            Spacer(Modifier.width(12.dp))

            Button(
                onClick = { ServerController.stopServer() },
                enabled = isRunning
            ) {
                Text("Stop Server")
            }
        }
    }
}
