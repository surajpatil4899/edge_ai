package com.edgeai.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Everything the assistant can do, as phrases to say; Try runs one as if it had been spoken. */
@Composable
fun HelpScreen(onTry: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What you can say 🗣️", style = MaterialTheme.typography.headlineSmall.copy(brush = Brand.primaryGradient), fontWeight = FontWeight.Bold)
                Text(
                    "Say \"Okay Edge\", wait for the beep, then speak normally. You don't have to use these exact words.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { Tips() }
        itemsIndexed(EXAMPLE_GROUPS, key = { _, g -> g.title }) { i, group ->
            val accent = Brand.accent(i)
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Brand.tint(i, isSystemInDarkTheme()))) {
                Column(Modifier.padding(vertical = 12.dp, horizontal = 4.dp)) {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                            Text(group.emoji, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.size(10.dp))
                        Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    group.note?.let {
                        Text(it, Modifier.padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    group.examples.forEach { TryRow(it, accent) { onTry(it) } }
                }
            }
        }
        item {
            Text(
                "Not yet: questions like \"what's the weather\" (it can search the web for you instead), " +
                    "steps inside apps beyond opening them at the right place, and languages other than English.",
                Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Tips() {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brand.primaryGradient)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("💡 Tips", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            listOf(
                "Speak after the beep, at a normal pace, from up to an arm's length away.",
                "If \"Okay Edge\" doesn't respond, tap the mic instead. The wake word is still being tuned to different voices.",
                "Calls start straight away; messages open ready to send, and you tap send.",
                "Got a wrong result? Tap the thumbs-down under it. That tells us exactly what to fix.",
            ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = Color.White) }
        }
    }
}
