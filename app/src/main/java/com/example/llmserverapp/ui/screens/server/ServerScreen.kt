package com.example.llmserverapp.ui.screens.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llmserverapp.ServerController

@Composable
fun ServerScreen() {
    val isRunning by ServerController.isRunning.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {

        // Header
        Text(
            "Server",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        // Server Status Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Server Status",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (isRunning) "Running" else "Stopped",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isRunning) {
                TextButton(
                    onClick = { ServerController.startServer() }
                ) {
                    Text("Start")
                }
            } else {
                TextButton(
                    onClick = { ServerController.stopServer() }
                ) {
                    Text("Stop")
                }
            }
        }

        HorizontalDivider()
    }
}
