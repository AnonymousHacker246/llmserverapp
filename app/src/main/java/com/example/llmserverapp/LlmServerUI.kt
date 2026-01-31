package com.example.llmserverapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llmserverapp.core.logging.LogBuffer
import com.example.llmserverapp.core.logging.LogEntry
import com.example.llmserverapp.core.models.ModelDescriptor
import com.example.llmserverapp.core.models.ModelManager
import com.example.llmserverapp.ui.theme.Dimens
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmServerUI() {
    val context = LocalContext.current

    val isRunning by ServerController.isRunning.collectAsState()
    val logs by LogBuffer.logs.collectAsState()

    var expanded by remember { mutableStateOf(false) }

    val models by ModelManager.models.collectAsState()
    var selectedModel by remember { mutableStateOf<String?>(null) }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local LLM Server") }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(Dimens.lg),
                verticalArrangement = Arrangement.spacedBy(Dimens.lg)
            ) {

                // -----------------------------
                // Model Selection Card
                // -----------------------------
                item {
                    ModelSelectionCard(
                        models = models,
                        selectedModel = selectedModel,
                        expanded = expanded,
                        onExpand = { expanded = it },
                        onSelect = { selectedModel = it }
                    )
                }

                // -----------------------------
                // Server Controls Card
                // -----------------------------
                item {
                    ServerControlsCard(isRunning = isRunning)
                }

                // -----------------------------
                // Logs Section
                // -----------------------------
                item {
                    Text(
                        "Server Logs",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                item {
                    LogsCard(logs = logs)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectionCard(
    models: List<ModelDescriptor>,
    selectedModel: String?,
    expanded: Boolean,
    onExpand: (Boolean) -> Unit,
    onSelect: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(Dimens.lg)) {

            Text(
                "Model Selection",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(Dimens.md))

            if (models.isEmpty()) {
                Text(
                    "No GGUF models found.",
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { onExpand(!expanded) }
                ) {
                    TextField(
                        value = selectedModel?.let { File(it).name } ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Choose model") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded)
                        },
                        colors = ExposedDropdownMenuDefaults.textFieldColors(),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { onExpand(false) }
                    ) {
                        models.forEach { file ->
                            DropdownMenuItem(
                                text = { Text(file.name) },
                                onClick = {
                                    onSelect(file.localPath)
                                    onExpand(false)
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Dimens.md))

                Button(
                    onClick = {
                        selectedModel?.let { path ->
                            val fileName = File(path).name
                            LogBuffer.info("Loading model: $fileName")
                            LlamaBridge.unloadModel()
                            val result = LlamaBridge.loadModel(path)
                            if (result != 0L) {
                                LogBuffer.info("Model Loaded ✓")
                                ServerController.modelPath = path
                            } else {
                                LogBuffer.info("Model Failed to load (code: $result)")
                            }
                        }
                    },
                    enabled = selectedModel != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Load Model")
                }

                Button(
                    onClick = {
                        LogBuffer.info("Running benchmark…")
                        ServerController.runBenchmark()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Run Benchmark")
                }
            }
        }
    }
}

@Composable
private fun ServerControlsCard(isRunning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(Dimens.lg)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor =
                    if (isRunning) Color(0xFF4CAF50) else Color(0xFFF44336)

                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(statusColor, CircleShape)
                )

                Spacer(Modifier.width(Dimens.sm))

                Text(
                    text = if (isRunning) "Server Running" else "Server Stopped",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(Dimens.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { ServerController.startServer() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Start")
                }

                Spacer(Modifier.width(Dimens.md))

                Button(
                    onClick = { ServerController.stopServer() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun LogsCard(logs: List<LogEntry>) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        LazyColumn(
            modifier = Modifier.padding(Dimens.md),
            state = listState
        ) {
            items(logs) { log ->
                Text(
                    text = log.message,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}


