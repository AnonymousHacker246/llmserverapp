package com.example.llmserverapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.CheckCircle
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.llmserverapp.ServerController

@Composable
fun ServerScreen() {

    val isRunning by ServerController.isRunning.collectAsState()
    val loadedModel by ServerController.loadedModel.collectAsState()
    val metrics by ServerController.metrics.collectAsState()
    val settings by ServerController.settings.collectAsState()
    val uptime by ServerController.uptime.collectAsState()

    var showMetricsPopup by remember { mutableStateOf(false) }
    var showSettingsPopup by remember { mutableStateOf(false) }
    var showRequestsPopup by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf(settings.port.toString()) }
    var maxTokensText by remember { mutableStateOf(settings.maxTokens.toString()) }
    var temperatureText by remember { mutableStateOf(settings.temperature.toString()) }
    var threadsText by remember { mutableStateOf(settings.threads.toString()) }


    // -------------------------
    // Popup Block (unused now)
    // -------------------------
    @Composable
    fun PopupBlock(
        onDismiss: () -> Unit,
        content: @Composable ColumnScope.() -> Unit
    ) { /* unchanged */
    }

    // -------------------------
    // Block Outline
    // -------------------------
    @Composable
    fun BlockOutline(
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) { /* unchanged */
    }

    @Composable
    fun ThinNumberField(
        value: String,
        onValueChange: (String) -> Unit,
        onNumberCommit: (Int) -> Unit,
        modifier: Modifier = Modifier
    ) {
        BasicTextField(
            value = value,
            onValueChange = { new ->
                if (new.isEmpty()) {
                    onValueChange("")
                    return@BasicTextField
                }
                if (new.all { it.isDigit() }) {
                    onValueChange(new)
                    new.toIntOrNull()?.let(onNumberCommit)
                }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            modifier = modifier
                .width(60.dp)
                .padding(vertical = 4.dp),
            decorationBox = { inner ->
                Column {
                    inner()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    )
                }
            }
        )
    }


    @Composable
    fun EditableNumberRow(
        label: String,
        value: Int,
        onValueChange: (Int) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            // verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)

            TextField(
                value = value.toString(),
                onValueChange = { new ->
                    new.toIntOrNull()?.let(onValueChange)
                },
                singleLine = true,
                modifier = Modifier.width(100.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    @Composable
    fun EditableFloatRow(
        label: String,
        value: Float,
        onValueChange: (Float) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            // verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)

            TextField(
                value = value.toString(),
                onValueChange = { new ->
                    new.toFloatOrNull()?.let(onValueChange)
                },
                singleLine = true,
                modifier = Modifier.width(100.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
    }

    @Composable
    fun ReadOnlyRow(
        label: String,
        value: String
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(12.dp))
    }

    // -------------------------
    // Bottom Sheet
    // -------------------------
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun InfoBottomSheet(
        visible: Boolean,
        onDismiss: () -> Unit,
        content: @Composable ColumnScope.() -> Unit
    ) {
        if (!visible) return

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() },
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp), // ⭐ smaller padding
                verticalArrangement = Arrangement.spacedBy(0.dp)   // ⭐ tighter spacing
            ) {
                content()
            }
        }


    }

    // -------------------------
    // Toggle Tile
    // -------------------------
    @Composable
    fun ToggleTile(
        label: String,
        icon: @Composable (Modifier) -> Unit,
        isActive: Boolean,
        onToggle: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Row(
            modifier = modifier
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.extraLarge
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    shape = MaterialTheme.shapes.extraLarge
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        if (isActive)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        else Color.Transparent,
                        CircleShape
                    )
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center
            ) {
                icon(Modifier)
            }

            Text(label, style = MaterialTheme.typography.headlineMedium)
        }
    }

    // -------------------------
    // Basic Block
    // -------------------------
    @Composable
    fun Block(
        modifier: Modifier = Modifier,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Column(
            modifier = modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.shapes.medium
                )
                .padding(20.dp)
        ) {
            content()
        }
    }

    // -------------------------
    // MAIN LAYOUT
    // -------------------------
    // -------------------------
// MAIN LAYOUT
// -------------------------
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        BlockOutline(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .height(80.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text("Server", style = MaterialTheme.typography.headlineMedium)
                Text("Uptime: ${uptime}s")
            }
        }

        ToggleTile(
            icon = { iconModifier ->
                Icon(
                    imageVector = Icons.Sharp.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = iconModifier.size(28.dp)
                )
            },
            isActive = isRunning,
            onToggle = {
                if (isRunning) ServerController.stopServer()
                else ServerController.startServer()
            },
            label = "Status",
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .height(60.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Block(
                modifier = Modifier
                    .widthIn(min = 160.dp, max = 220.dp)
                    .height(160.dp)
            ) {
                Text("Endpoint", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { ServerController.copyEndpoint() }) {
                    Text(ServerController.endpoint)
                }
            }

            Block(
                modifier = Modifier
                    .weight(1f)
                    .height(160.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Loaded Model", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(loadedModel ?: "None")
                }
            }
        }

        // ⭐ RESTORED BLOCKS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Block(
                modifier = Modifier
                    .weight(1f)
                    .clickable { showMetricsPopup = true }
            ) {
                Text("Metrics", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Tap to expand")
            }

            Block(
                modifier = Modifier
                    .weight(1f)
                    .clickable { showSettingsPopup = true }
            ) {
                Text("Settings", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Tap to expand")
            }
        }

    }


    // -------------------------
    // BOTTOM SHEETS (correct placement)
    // -------------------------
    InfoBottomSheet(
        visible = showMetricsPopup,
        onDismiss = { showMetricsPopup = false }
    ) {
        Text("Metrics", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Text("Tokens/sec: %.2f".format(metrics.tokensPerSec))
        Text("Last: ${metrics.lastRequestMs}ms")
        Text("Requests: ${metrics.requestCount}")
        Text("Total: ${metrics.totalTokens}")
    }

    InfoBottomSheet(
        visible = showSettingsPopup,
        onDismiss = { showSettingsPopup = false }
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        // Port
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Port")
            ThinNumberField(
                value = portText,
                onValueChange = {new ->
                    portText = new
                    new.toIntOrNull()?.let{
                        ServerController.updatePort(it)
                    } },
                onNumberCommit = { ServerController.updatePort(it) }
            )
        }

        // Max Tokens
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Max Tokens")
            ThinNumberField(
                value = maxTokensText,
                onValueChange = { new ->
                    maxTokensText = new
                    new.toIntOrNull()?.let {
                        ServerController.updateMaxTokens(it)
                    }
                },
                onNumberCommit = { ServerController.updateMaxTokens(it) }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Temperature")
            ThinNumberField(
                value = temperatureText,
                onValueChange = { new ->
                    temperatureText = new
                    new.toIntOrNull()?.let {
                        ServerController.updateTemperature(it.toFloat())
                    }
                },
                onNumberCommit = { ServerController.updateTemperature(it.toFloat()) }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Threads")
            ThinNumberField(
                value = threadsText,
                onValueChange = { new ->
                    threadsText = new
                    new.toIntOrNull()?.let{
                        ServerController.updateThreads(it)
                    }},
                onNumberCommit = { ServerController.updateThreads(it) }
            )
        }

        // Context Length (read‑only)
        ReadOnlyRow(
            label = "Context Length",
            value = settings.contextLength.toString()
        )
    }


}