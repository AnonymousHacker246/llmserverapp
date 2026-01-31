package com.example.llmserverapp.ui.screens.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.llmserverapp.core.models.ModelDescriptor
import com.example.llmserverapp.core.models.ModelManager
import com.example.llmserverapp.core.models.ModelStatus
import com.example.llmserverapp.core.utils.prettySize

@Composable
fun ModelsScreen() {
    val context = LocalContext.current
    val models by ModelManager.models.collectAsState()

    LaunchedEffect(Unit) {
        ModelManager.init(context)
    }

    LazyColumn {
        items(models) { model ->
            ModelRow(
                model = model,
                onDownload = { ModelManager.download(model.id) },
                onLoad = { ModelManager.load(model.id) }
            )
        }
    }
}

@Composable
fun ModelRow(
    model: ModelDescriptor,
    onDownload: () -> Unit,
    onLoad: () -> Unit
) {
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
                Text(
                    text = model.prettyName,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = model.sizeBytes.prettySize(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when (model.status) {
                ModelStatus.NotDownloaded ->
                    TextButton(onClick = onDownload) { Text("Download") }

                ModelStatus.Downloading ->
                    CircularProgressIndicator(
                        progress = { model.progress / 100f },
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )

                ModelStatus.Downloaded ->
                    TextButton(onClick = onLoad) { Text("Load") }

                ModelStatus.Loading ->
                    Text("Loading…", color = MaterialTheme.colorScheme.primary)

                ModelStatus.Loaded -> {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Loaded",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(22.dp)
                    )
                }
                ModelStatus.Error ->
                    TextButton(onClick = onDownload) { Text("Retry") }
            }
        }

        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

/*
@Composable
fun ModelCard(
    model: ModelDescriptor,
    onDownload: () -> Unit,
    onLoad: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(model.prettyName, style = MaterialTheme.typography.titleMedium)
            Text(model.sizeBytes.prettySize(), style = MaterialTheme.typography.bodySmall)

            when (model.status) {
                ModelStatus.NotDownloaded -> {
                    Button(onClick = onDownload) { Text("Download") }
                }
                ModelStatus.Downloading -> {
                    LinearProgressIndicator(progress = { model.progress / 100f })
                    Text("${model.progress}%")
                }
                ModelStatus.Downloaded -> {
                    Button(onClick = onLoad) { Text("Load") }
                }
                ModelStatus.Loading -> {
                    Text("Loading…")
                }
                ModelStatus.Loaded -> {
                    Text("Loaded ✓")
                }
                ModelStatus.Error -> {
                    Text("Error downloading")
                    Button(onClick = onDownload) { Text("Retry") }
                }
            }
        }
    }
}*/
