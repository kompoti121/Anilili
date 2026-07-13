package com.miruronative.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miruronative.data.update.UpdateManager

/**
 * Renders the update flow dialogs (available -> downloading -> install / failed)
 * driven by [UpdateManager.state]. Mounted once at the app root.
 */
@Composable
fun UpdatePromptHost() {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()

    when (val s = state) {
        is UpdateManager.State.Available -> AlertDialog(
            onDismissRequest = UpdateManager::dismiss,
            title = { Text("Update available", fontWeight = FontWeight.Black) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "Anilili v${s.update.version} (you have v${UpdateManager.currentVersion})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (s.update.changelog.isNotBlank()) {
                        Text(
                            formatChangelog(s.update.changelog),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { UpdateManager.download(context) }) { Text("Update") }
            },
            dismissButton = {
                TextButton(onClick = UpdateManager::dismiss) { Text("Not now") }
            },
        )

        is UpdateManager.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update", fontWeight = FontWeight.Black) },
            text = {
                Column {
                    Text(
                        "Anilili v${s.update.version}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp),
                    )
                }
            },
            confirmButton = {},
        )

        is UpdateManager.State.ReadyToInstall -> AlertDialog(
            onDismissRequest = UpdateManager::dismiss,
            title = { Text("Ready to install", fontWeight = FontWeight.Black) },
            text = {
                Text(
                    "Anilili v${s.update.version} is downloaded. If nothing happened, allow installs " +
                        "from this app when Android asks, then tap Install again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { UpdateManager.install(context) }) { Text("Install") }
            },
            dismissButton = {
                TextButton(onClick = UpdateManager::dismiss) { Text("Later") }
            },
        )

        is UpdateManager.State.Failed -> AlertDialog(
            onDismissRequest = UpdateManager::dismiss,
            title = { Text("Update failed", fontWeight = FontWeight.Black) },
            text = { Text(s.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = UpdateManager::dismiss) { Text("Close") }
            },
        )

        else -> Unit
    }
}

/** Strips the most common markdown noise from release notes for plain-text display. */
private fun formatChangelog(body: String): String =
    body.lines()
        .map { line ->
            line
                .replace(Regex("""^#+\s*"""), "")
                .replace("**", "")
                .replace("`", "")
                .replace(Regex("""^\s*[-*]\s+"""), "• ")
        }
        .joinToString("\n")
        .trim()
