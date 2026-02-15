package com.example.llmserverapp.ui.screens.models


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.llmserverapp.core.models.ModelDescriptor
import com.example.llmserverapp.core.models.ModelManager
import com.example.llmserverapp.core.models.ModelManager.prettySize
import com.example.llmserverapp.core.models.ModelStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen() {

    data class ChatMessage(
        val text: String,
        val isUser: Boolean
    )

    var menuExpanded by remember { mutableStateOf(false) }
    var showDownloadSheet by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }

    val models by ModelManager.models.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()


    LaunchedEffect(messages.size) {
        listState.animateScrollToItem(messages.size)
    }
    LaunchedEffect(Unit) {
        ModelManager.refreshModels()
    }

    if (showDownloadSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDownloadSheet = false }
        ) {
            DownloadModelsSheet(
                onClose = { showDownloadSheet = true }
            )
        }
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
                        DropdownMenuItem(
                            text = { Text("Download Models") },
                            onClick = {
                                menuExpanded = false
                                showDownloadSheet = true
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Text(
                            msg.text,
                            modifier = Modifier.padding(8.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .then(
                                    if (msg.isUser)
                                        Modifier.padding(start = 48.dp)
                                    else
                                        Modifier.padding(end = 48.dp)
                                ),
                            color = if(msg.isUser) Color.White else Color.Black,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type prompt...") },
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true
                )
                TextButton(
                    onClick = {
                        if (text.isNotBlank()) {
                            val prompt = text
                            messages = messages + ChatMessage(text, isUser = true)
                            text = ""

                            scope.launch {
                                val response = ModelManager.runInference(prompt)
                                messages = messages + ChatMessage(response, isUser = false)
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(top = 8.dp)
                ) {
                    Text("Submit")
                }
            }
        }
    }
}


@Composable
fun DownloadModelsSheet(onClose: () -> Unit) {
    val models by ModelManager.models.collectAsState()

    LaunchedEffect(Unit) {
        ModelManager.refreshModels()
    }

    Column(Modifier.padding(16.dp)) {
        Text(
            "Download Models",
            style = MaterialTheme.typography.titleLarge
        )

        LazyColumn(Modifier.padding(top = 16.dp)) {
            items(models) { model ->
                DownloadModelRow(model)
            }
        }
    }
}

@Composable
fun DownloadModelRow(model: ModelDescriptor) {

    val percent = remember(model.progress) {
        (model.progress?.times(100)?.toInt() ?: 0)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(model.prettyName, style = MaterialTheme.typography.titleMedium)
            Text(prettySize(model.sizeBytes), color = Color.Gray)

            if (model.status == ModelStatus.Downloading && model.progress != null) {
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        when (model.status) {
            ModelStatus.NotDownloaded ->
                TextButton(onClick = { ModelManager.downloadModel(model.id) }) {
                    Text("Download")
                }

            ModelStatus.Downloading ->
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    LinearProgressIndicator(
                        progress = { model.progress ?: 0f },
                        modifier = Modifier
                            .size(width = 80.dp, height = 4.dp)
                    )
                }

            ModelStatus.Downloaded ->
                TextButton(onClick = { ModelManager.loadModel(model.id) }){
                    Text("Load")
                }

            ModelStatus.Failed ->
                TextButton(onClick = { ModelManager.refreshModels(force = true) }) {
                    Text("Retry")
                }

            ModelStatus.Loaded ->
                Text("Loaded", color = Color.Green)
        }
    }
}
