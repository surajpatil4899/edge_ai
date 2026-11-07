package com.edgeai.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** What the assistant is doing, as the user sees it. */
enum class Face { Off, Loading, Ready, Listening, Thinking, Speaking, Error }

@Composable
private fun currentFace(): Face {
    val status by AssistantState.status.collectAsStateWithLifecycle()
    val thinking by AssistantState.thinking.collectAsStateWithLifecycle()
    val speaking by Speaker.speaking.collectAsStateWithLifecycle()
    return when {
        speaking -> Face.Speaking
        thinking || status == Status.Transcribing -> Face.Thinking
        status == Status.Listening -> Face.Listening
        status == Status.Loading -> Face.Loading
        status == Status.WaitingForWake -> Face.Ready
        status == Status.Error -> Face.Error
        else -> Face.Off
    }
}

/**
 * The one screen testers live on: what they said and what the assistant did, a big mic button, and a text box
 * for when speaking isn't convenient. Voice and typed commands share the same conversation.
 */
@Composable
fun AssistantScreen(onMic: () -> Unit, onSend: (String) -> Unit, onOpenHelp: () -> Unit, onShareLogs: () -> Unit) {
    val turns by LlmChat.turns.collectAsStateWithLifecycle()
    val face = currentFace()
    val wakeWord by Prefs.wakeWord.state.collectAsStateWithLifecycle()
    val showDetails by Prefs.showDetails.state.collectAsStateWithLifecycle()
    val lastExit by AssistantState.lastExit.collectAsStateWithLifecycle()
    val server by LlmServer.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size, turns.lastOrNull()?.pending, turns.lastOrNull()?.action) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
    }

    val scheme = MaterialTheme.colorScheme
    val backdrop = Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.7f), scheme.background, scheme.background))
    Column(Modifier.fillMaxSize().background(backdrop).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Edge Assistant", style = MaterialTheme.typography.titleLarge.copy(brush = Brand.primaryGradient), fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" Runs on your phone, works offline", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Switch(checked = wakeWord, onCheckedChange = { Prefs.set(Prefs.wakeWord, it) })
                Text("\"Okay Edge\"", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        lastExit?.let { reason ->
            Banner("The app stopped unexpectedly last time ($reason). Sharing the logs helps us fix it.", "Share logs", onShareLogs) {
                AssistantState.lastExit.value = null
            }
        }
        (server as? LlmServer.State.Failed)?.let {
            Banner("The command model couldn't start: ${it.message}", "Share logs", onShareLogs, onDismiss = null)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (turns.isEmpty()) {
                Welcome(wakeWord, onSend, onOpenHelp)
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(), state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(turns, key = { it.id }) { Exchange(it, showDetails) }
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // The welcome text already says how to start; repeat the hint only once there is a conversation.
            if (turns.isNotEmpty() || face != Face.Ready) Text(
                faceHint(face, wakeWord), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            MicOrb(face, onClick = {
                if (face == Face.Speaking) Speaker.stop()
                onMic()
            })
            Spacer(Modifier.size(8.dp))
            TypeBox(onSend)
        }
    }
}

private fun faceHint(face: Face, wakeWord: Boolean) = when (face) {
    Face.Off -> "Tap the mic to start"
    Face.Loading -> "Getting ready… the first start takes a few seconds"
    Face.Ready -> if (wakeWord) "Say \"Okay Edge\", wait for the beep, then your command. Or tap the mic." else "Tap the mic and speak"
    Face.Listening -> "I'm listening… 👂"
    Face.Thinking -> "On it… ⚡"
    Face.Speaking -> "Tap the mic to interrupt"
    Face.Error -> "Something went wrong. Tap the mic to try again."
}

@Composable
private fun MicOrb(face: Face, onClick: () -> Unit) {
    val level by AssistantState.level.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    // Two colours per state, blended into the button's gradient: violet-blue ready, blue-teal listening,
    // pink-amber thinking and speaking.
    val (from, to) = when (face) {
        Face.Off, Face.Loading -> scheme.outline to scheme.outlineVariant
        Face.Ready -> Brand.violet to Brand.blue
        Face.Listening -> Brand.blue to Brand.teal
        Face.Thinking, Face.Speaking -> Brand.pink to Brand.amber
        Face.Error -> scheme.error to Brand.coral
    }
    val a by animateColorAsState(from, label = "orb from")
    val b by animateColorAsState(to, label = "orb to")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val breath by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "breath")
    val spin by pulse.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spin")
    val voice by animateFloatAsState(if (face == Face.Listening) 1f + level * 0.7f else 1f, tween(90), label = "voice")

    Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            val glow = { scale: Float, alpha: Float ->
                drawCircle(Brush.radialGradient(listOf(a.copy(alpha = alpha), b.copy(alpha = 0f)), center, r * scale), radius = r * scale)
            }
            when (face) {
                Face.Ready, Face.Speaking -> glow(0.85f * breath, 0.35f)
                Face.Listening -> {
                    glow((0.8f * voice).coerceAtMost(1f), 0.45f)
                    drawCircle(b.copy(alpha = 0.25f), radius = (r * 0.66f * voice).coerceAtMost(r), style = Stroke(3.dp.toPx()))
                }
                Face.Thinking, Face.Loading -> {
                    glow(0.8f, 0.25f)
                    drawArc(
                        Brush.sweepGradient(listOf(a, b, a)), startAngle = spin, sweepAngle = 140f, useCenter = false,
                        topLeft = Offset(center.x - r * 0.7f, center.y - r * 0.7f),
                        size = androidx.compose.ui.geometry.Size(r * 1.4f, r * 1.4f), style = Stroke(width = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
                else -> Unit
            }
        }
        Box(
            Modifier.size(86.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a, b))).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(if (face == Face.Speaking) R.drawable.ic_stop else R.drawable.ic_mic), "Talk", Modifier.size(38.dp), tint = Color.White)
        }
    }
}

@Composable
private fun TypeBox(onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    fun send() {
        if (text.isNotBlank()) onSend(text.trim())
        text = ""
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
            placeholder = { Text("Or type a command ✍️") }, singleLine = true, shape = RoundedCornerShape(28.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedBorderColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
        )
        AnimatedVisibility(text.isNotBlank()) {
            Box(
                Modifier.padding(start = 8.dp).size(48.dp).clip(CircleShape).background(Brand.primaryGradient).clickable(onClick = ::send),
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_send), "Send", Modifier.size(22.dp), tint = Color.White) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(wakeWord: Boolean, onSend: (String) -> Unit, onOpenHelp: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Brand.warmGradient), contentAlignment = Alignment.Center) {
            Text("👋", style = MaterialTheme.typography.headlineLarge)
        }
        Spacer(Modifier.size(16.dp))
        Text(greeting(), style = MaterialTheme.typography.headlineSmall.copy(brush = Brand.primaryGradient), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("What can I do for you?", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(
            if (wakeWord) "Say \"Okay Edge\", wait for the beep, then speak. Or tap a suggestion to try it."
            else "Tap the mic and speak, or tap a suggestion to try it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        val dark = isSystemInDarkTheme()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SUGGESTIONS.forEachIndexed { i, (emoji, s) ->
                Surface(onClick = { onSend(s) }, shape = RoundedCornerShape(20.dp), color = Brand.tint(i, dark)) {
                    Text("$emoji  $s", Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        TextButton(onClick = onOpenHelp) { Text("See everything you can say") }
    }
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning! ☀️"
    in 12..16 -> "Good afternoon! 🌤️"
    in 17..21 -> "Good evening! 🌆"
    else -> "Hello, night owl! 🦉"
}

/** The reply's avatar: what kind of thing was done (⏰ alarm, 🎵 music, 🔍 search, …). */
private fun replyEmoji(turn: ChatTurn): String = when {
    turn.action?.ok == false -> "🤔"
    turn.guard?.contains("web search") == true -> "🔍"
    turn.guard == "help" -> "💡"
    turn.guard == "timer control" -> "⏲️"
    else -> emojiFor(turn.call?.name)
}

/** Three dots that bounce while the reply is on its way. */
@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val y by t.animateFloat(0f, -6f, infiniteRepeatable(tween(380, delayMillis = i * 120), RepeatMode.Reverse), label = "dot $i")
            Box(Modifier.graphicsLayer { translationY = y.dp.toPx() }.size(8.dp).clip(CircleShape).background(Brand.accent(i)))
        }
    }
}

/** One command and its reply: the user's words on the right, what the assistant did on the left. */
@Composable
private fun Exchange(turn: ChatTurn, showDetails: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)).background(Brand.primaryGradient)) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 280.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (turn.fromVoice) {
                        Icon(painterResource(R.drawable.ic_mic), "Spoken", Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.8f))
                        Spacer(Modifier.size(6.dp))
                    }
                    Text(turn.user, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Bottom) {
            val dark = isSystemInDarkTheme()
            Box(Modifier.size(34.dp).clip(CircleShape).background(Brand.tint(turn.id.toInt(), dark)), contentAlignment = Alignment.Center) {
                Text(if (turn.pending || turn.action == null) "✨" else replyEmoji(turn), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.size(8.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val action = turn.action
                    when {
                        // Still running: the model, or the action after it (typed commands only show the call in developer mode).
                        turn.pending || (action == null && turn.error == null && (turn.fromVoice || LlmChat.runTypedActions.value)) ->
                            TypingDots()
                        turn.error != null -> Text("Sorry, something went wrong.", color = MaterialTheme.colorScheme.error)
                        action != null -> Row(verticalAlignment = Alignment.Top) {
                            val good = if (isSystemInDarkTheme()) StatusColors.goodDark else StatusColors.good
                            if (action.ok) {
                                Icon(painterResource(R.drawable.ic_check), "Done", Modifier.size(18.dp).padding(top = 2.dp), tint = good)
                                Spacer(Modifier.size(6.dp))
                            }
                            Text(action.say, style = MaterialTheme.typography.bodyLarge, color = if (action.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> Text(
                            turn.calls.joinToString("\n") { c -> c.name + c.args.joinToString(", ", " (", ")") { "${it.first}: ${it.second}" } }
                                .ifBlank { "No action for that." },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (showDetails) Details(turn)
                }
            }
        }
        if (!turn.pending && turn.action != null) Feedback(turn) { good -> LlmChat.setFeedback(context, turn.id, good) }
    }
}

@Composable
private fun Feedback(turn: ChatTurn, onFeedback: (Boolean) -> Unit) {
    Row(Modifier.padding(start = 46.dp), verticalAlignment = Alignment.CenterVertically) {
        when (turn.feedback) {
            null -> {
                Text("Did I get it right?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onFeedback(true) }, Modifier.size(36.dp)) {
                    Icon(painterResource(R.drawable.ic_thumb_up), "Right", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onFeedback(false) }, Modifier.size(36.dp)) {
                    Icon(painterResource(R.drawable.ic_thumb_down), "Wrong", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            true -> Text("Thanks! 🙌", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            false -> Text("Thanks, we'll fix that 🛠️", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

/** Testers' view of what happened inside: calls, guard decisions, timings, raw model output. */
@Composable
private fun Details(turn: ChatTurn) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        turn.calls.forEachIndexed { i, c ->
            Text("${if (turn.calls.size > 1) "${i + 1}. " else ""}${c.name}(${c.args.joinToString { "${it.first}=${it.second}" }})", style = mono, color = muted)
        }
        turn.guard?.let { Text("guard: $it", style = mono, color = MaterialTheme.colorScheme.tertiary) }
        if (turn.raw.isNotBlank()) {
            Text(
                "model ${turn.totalMillis} ms (prefill ${turn.prefillTokens} tok %.0f ms, decode %.0f ms) · action ${turn.actionMillis} ms"
                    .format(turn.prefillMillis, turn.decodeMillis),
                style = mono, color = muted,
            )
            Text(turn.raw, style = mono, color = muted)
        }
    }
}

@Composable
private fun Banner(message: String, action: String, onAction: () -> Unit, onDismiss: (() -> Unit)?) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(Modifier.align(Alignment.End)) {
                onDismiss?.let { TextButton(onClick = it) { Text("Dismiss") } }
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

/** An example command with a Try button, for the Help tab. */
@Composable
fun TryRow(text: String, accent: Color = Brand.violet, onTry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onTry).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("\"$text\"", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Surface(shape = CircleShape, color = accent) {
            Text("Try ▶", Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}
