package com.example.llmserverapp.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.llmserverapp.core.models.ModelDescriptor
import com.example.llmserverapp.core.models.ModelManager
import com.example.llmserverapp.core.models.ModelStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen() {
    val models by ModelManager.models.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ModelManager.refreshModels()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Run Benchmark") },
                            onClick = {
                                menuExpanded = false
                                ModelManager.runBenchmark()
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .padding(top = 64.dp)
        ) {
            items(models) { model ->
                ModelRow(model)
            }
        }
    }
}

@Composable
fun ModelRow(model: ModelDescriptor) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(model.prettyName, style = MaterialTheme.typography.titleMedium)
                Text(model.fileName, style = MaterialTheme.typography.bodySmall)
            }

            when (model.status) {
                ModelStatus.NotDownloaded ->
                    TextButton(onClick = { ModelManager.downloadModel(model.id) }) {
                        Text("Download")
                    }

                ModelStatus.Downloaded ->
                    TextButton(onClick = { ModelManager.loadModel(model.id) }) {
                        Text("Load")
                    }

                ModelStatus.Loaded ->
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Loaded",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(22.dp)
                    )

                ModelStatus.Downloading -> {
                    val pct = ((model.progress ?: 0f) * 100).toInt()
                    Text("$pct%")
                }
            }
        }

        // --- Progress bar under the row, above the divider ---
        if (model.status == ModelStatus.Downloading) {
            LinearProgressIndicator(
                progress = { model.progress ?: 0f },
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(4.dp)
            )
        }

        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
