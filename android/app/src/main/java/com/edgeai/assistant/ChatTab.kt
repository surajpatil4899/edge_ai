package com.edgeai.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val EXAMPLES = listOf(
    "Set an alarm for 6 am tomorrow",
    "Set a timer for 10 minutes",
    "Remind me to buy milk at 5 pm",
    "Call mom",
    "Send a message to Rahul saying I will be late",
    "Turn on the flashlight",
    "Pause the music",
    "Saturn reminded that I want to go to hospital tomorrow",
    "Calm mom",
    "What are you doing",
    "What can you do",
    "Set an alarm after 6 hours",
    "Stop the alarm of six am",
    "Open youtube and play a bollywood song",
    "Play Arijit Singh on Spotify",
    "Turn on do not disturb and set an alarm for 6",
    "Open WhatsApp and message Rahul I'm late",
    "Navigate to the airport",
    "Open the camera in selfie mode",
)

@Composable
fun ChatTab(onLoadModel: () -> Unit, onUnloadModel: () -> Unit, onSend: (String) -> Unit) {
    val server by LlmServer.state.collectAsStateWithLifecycle()
    val turns by LlmChat.turns.collectAsStateWithLifecycle()
    val runTyped by LlmChat.runTypedActions.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val ready = server is LlmServer.State.Ready
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size, turns.lastOrNull()?.pending) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
    }

    fun send(text: String) {
        if (text.isNotBlank() && ready) onSend(text.trim())
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("FunctionGemma 270M (on device)", style = MaterialTheme.typography.titleSmall)
                Text(
                    when (val s = server) {
                        LlmServer.State.Stopped -> "Not loaded"
                        LlmServer.State.Starting -> "Loading model and function schema…"
                        is LlmServer.State.Ready -> "Ready: ${s.model} (loaded in ${s.loadMillis} ms)"
                        is LlmServer.State.Failed -> "Failed: ${s.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (server is LlmServer.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (server) {
                LlmServer.State.Starting -> CircularProgressIndicator(Modifier.size(24.dp))
                is LlmServer.State.Ready -> OutlinedButton(onClick = onUnloadModel) { Text("Unload") }
                else -> Button(onClick = onLoadModel) { Text("Load model") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Carry out typed commands", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Voice commands always run. Off: typed ones only show the function call.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = runTyped, onCheckedChange = { LlmChat.runTypedActions.value = it })
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(EXAMPLES) { ex -> AssistChip(onClick = { send(ex) }, enabled = ready, label = { Text(ex) }) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(turns, key = { it.id }) { TurnView(it) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (ready) "Type a command" else "Load the model first") },
                singleLine = true,
                enabled = ready,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input); input = "" }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { send(input); input = "" }, enabled = ready && input.isNotBlank()) { Text("Send") }
        }
        if (turns.isNotEmpty()) {
            TextButton(onClick = { LlmChat.clear() }, modifier = Modifier.align(Alignment.End)) { Text("Clear chat") }
        }
    }
}

@Composable
private fun TurnView(turn: ChatTurn) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.align(Alignment.End),
        ) {
            Text(
                (if (turn.fromVoice) "🎙 " else "") + turn.user,
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    turn.pending -> Text("Thinking…", style = MaterialTheme.typography.bodyMedium)
                    turn.error != null -> Text("Error: ${turn.error}", color = MaterialTheme.colorScheme.error)
                    turn.calls.isNotEmpty() -> for ((i, call) in turn.calls.withIndex()) {
                        val prefix = if (turn.calls.size > 1) "${i + 1}. " else ""
                        Text(prefix + call.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (call.args.isEmpty()) Text("(no arguments)", style = MaterialTheme.typography.bodySmall)
                        for ((k, v) in call.args) {
                            Text("$k = $v", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        }
                    }
                    turn.raw.isBlank() && turn.guard != null -> Text("Handled without the model", style = MaterialTheme.typography.titleSmall)
                    else -> Text("No function call", style = MaterialTheme.typography.titleSmall)
                }
                turn.guard?.let {
                    Text("guard: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                turn.action?.let { a ->
                    Text(
                        (if (a.ok) "✓ " else "✗ ") + "\"${a.say}\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (a.ok) androidx.compose.ui.graphics.Color(0xFF43A047) else MaterialTheme.colorScheme.error,
                    )
                }
                if (!turn.pending && turn.error == null && turn.raw.isNotBlank()) {
                    Text(
                        turn.raw.ifBlank { "(empty)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "prefill ${turn.prefillTokens} tok %.0f ms · decode ${turn.decodeTokens} tok %.0f ms · total ${turn.totalMillis} ms"
                            .format(turn.prefillMillis, turn.decodeMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
