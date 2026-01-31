package com.example.llmserverapp.ui.screens.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.llmserverapp.core.logging.LogBuffer
import com.example.llmserverapp.core.logging.LogEntry
import com.example.llmserverapp.core.logging.LogLevel

@Composable
fun LogsScreen() {
    val logs by LogBuffer.logs.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp),
        state = listState
    ) {
        items(logs) { entry ->
            LogRow(entry)
        }
    }
}

@Composable
fun LogRow(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.INFO -> Color(0xFF9CDCFE)
        LogLevel.WARN -> Color(0xFFFFC107)
        LogLevel.ERROR -> Color(0xFFFF6B6B)
        LogLevel.DEBUG -> Color(0xFFB5CEA8)
    }

    Text(
        text = buildString {
            if (entry.tag != null) append("[${entry.tag}] ")
            append(entry.message)
        },
        color = color,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
