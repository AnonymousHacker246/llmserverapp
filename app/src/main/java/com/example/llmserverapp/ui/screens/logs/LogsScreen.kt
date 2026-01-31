package com.example.llmserverapp.ui.screens.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import colorizeLogMessage
import com.example.llmserverapp.core.logging.LogBuffer
import com.example.llmserverapp.core.logging.LogEntry

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
fun formatTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

@Composable
fun LogHeader(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        androidx.compose.material3.Divider(
            color = Color(0xFF424242),
            modifier = Modifier.weight(1f)
        )

        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF90CAF9),
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        androidx.compose.material3.Divider(
            color = Color(0xFF424242),
            modifier = Modifier.weight(1f)
        )
    }
}



@Composable
fun LogRow(entry: LogEntry) {
    val tagColor = when (entry.tag) {
        "SERVER" -> Color(0xFF64B5F6)
        "MODEL" -> Color(0xFF81C784)
        "BENCHMARK" -> Color(0xFFFFB74D)
        "ERROR" -> Color(0xFFE57373)
        else -> Color.LightGray
    }
    val isHeader = entry.message.startsWith("===") ||
            entry.message.startsWith("----") ||
            entry.message.startsWith("HEADER:")

    if (isHeader) {
        val clean = entry.message
            .replace("=", "")
            .replace("-", "")
            .replace("HEADER:", "")
            .trim()

        LogHeader(clean)
        return
    }



    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Timestamp
        /*
        Text(
            text = formatTime(entry.timestampMillis),
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            modifier = Modifier.width(72.dp)
        )
        */

        // Tag
        /*
        Text(
            text = "[${entry.tag}]",
            style = MaterialTheme.typography.bodySmall,
            color = tagColor,
            modifier = Modifier.width(90.dp)
        )
        */

        // Colorized message
        Text(
            text = colorizeLogMessage(entry.message, tagColor),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
