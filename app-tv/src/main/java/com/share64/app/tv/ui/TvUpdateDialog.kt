package com.share64.app.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.share64.app.core.Formatting
import com.share64.app.core.MarkdownLite
import com.share64.app.core.ReleaseDates
import com.share64.app.core.UpdateManager
import com.share64.app.core.UpdateState
import com.share64.app.core.updateInfo
import com.share64.app.core.R as CoreR
import kotlinx.coroutines.delay

/** D-pad friendly "update available" dialog for the TV app. */
@Composable
fun TvUpdateDialog(state: UpdateState, currentVersion: String, onDismiss: () -> Unit) {
    val update = state.updateInfo ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val firstButton = remember { FocusRequester() }
    val scheme = MaterialTheme.colorScheme
    val notes = remember(update.notes) {
        val lines = MarkdownLite.toPlainText(MarkdownLite.parse(update.notes)).lines()
        lines.take(9).joinToString("\n") + if (lines.size > 9) "\n\u2026" else ""
    }

    TvDialog(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(
                modifier = Modifier.size(96.dp).clip(CircleShape).background(Color(0xFF070607)),
                contentAlignment = Alignment.Center,
            ) {
                Image(painter = painterResource(CoreR.drawable.logo_mark), contentDescription = null, modifier = Modifier.padding(18.dp))
            }
            Column {
                Text(
                    when (state) {
                        is UpdateState.Downloading -> "Downloading update\u2026"
                        is UpdateState.Ready -> "Ready to install"
                        is UpdateState.NeedsPermission -> "One more step"
                        is UpdateState.Installing -> "Installing\u2026"
                        is UpdateState.Failed -> "Update failed"
                        else -> "Update available"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "v$currentVersion  \u2192  v${update.version}",
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.primary,
                )
                Text(
                    listOfNotNull(
                        update.assetSize.takeIf { it > 0 }?.let { Formatting.bytes(it) },
                        update.abiLabel,
                        ReleaseDates.format(update.publishedAt),
                    ).joinToString(" \u00B7 "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        if (notes.isNotBlank()) {
            Text("What\u2019s new", style = MaterialTheme.typography.titleMedium, color = scheme.primary)
            Text(notes, style = MaterialTheme.typography.bodyLarge)
        }

        when (state) {
            is UpdateState.Downloading -> {
                val fraction = if (state.total > 0) (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(10.dp))
                Text(
                    "${Formatting.bytes(state.downloaded)} of ${Formatting.bytes(state.total)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            is UpdateState.NeedsPermission -> Text(
                "Allow SHARE64 to install updates: turn on \u201CAllow from this source\u201D, then come back.",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
            )
            is UpdateState.Installing -> Text("Waiting for Android to install\u2026 confirm if it asks.", style = MaterialTheme.typography.bodyLarge)
            is UpdateState.Ready -> state.note?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant) }
            is UpdateState.Failed -> Text(state.message, style = MaterialTheme.typography.bodyLarge, color = scheme.error)
            else -> Unit
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            when (state) {
                is UpdateState.Available -> {
                    TvButton("Update now", onClick = { UpdateManager.startUpdate() }, primary = true, modifier = Modifier.focusRequester(firstButton))
                    TvButton("Later", onClick = onDismiss)
                    TvButton("Skip this version", onClick = { UpdateManager.skipThisVersion() })
                }
                is UpdateState.Downloading -> TvButton("Cancel", onClick = { UpdateManager.cancelDownload() }, modifier = Modifier.focusRequester(firstButton))
                is UpdateState.Ready -> {
                    TvButton("Install now", onClick = { UpdateManager.startUpdate() }, primary = true, modifier = Modifier.focusRequester(firstButton))
                    TvButton("Later", onClick = onDismiss)
                }
                is UpdateState.NeedsPermission -> {
                    TvButton("Open settings", onClick = { UpdateManager.openInstallSettings(context) }, primary = true, modifier = Modifier.focusRequester(firstButton))
                    TvButton("Later", onClick = onDismiss)
                }
                is UpdateState.Failed -> {
                    TvButton("Try again", onClick = { UpdateManager.startUpdate() }, primary = true, modifier = Modifier.focusRequester(firstButton))
                    TvButton("Close", onClick = onDismiss)
                }
                is UpdateState.Installing -> TvButton("Hide", onClick = onDismiss, modifier = Modifier.focusRequester(firstButton))
                else -> Unit
            }
        }

        // Buttons change with each step: put the remote's focus back on the first one.
        LaunchedEffect(state::class) {
            delay(150)
            runCatching { firstButton.requestFocus() }
        }
    }
}
