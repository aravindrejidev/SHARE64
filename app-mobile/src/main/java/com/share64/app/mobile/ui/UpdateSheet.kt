package com.share64.app.mobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.share64.app.core.Formatting
import com.share64.app.core.MarkdownLite
import com.share64.app.core.ReleaseDates
import com.share64.app.core.UpdateInfo
import com.share64.app.core.UpdateManager
import com.share64.app.core.UpdateState
import com.share64.app.core.updateInfo
import com.share64.app.core.R as CoreR

/** "Update available" bottom sheet: version change, release notes, download progress and install. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(state: UpdateState, currentVersion: String, onDismiss: () -> Unit) {
    val update = state.updateInfo ?: return
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val blocks = remember(update.notes) { MarkdownLite.parse(update.notes) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
                .animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            UpdateHero()
            Text(titleFor(state), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            VersionChange(currentVersion, update.version)
            Text(
                metaLine(update),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("What\u2019s new", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (blocks.isEmpty()) {
                        Text(
                            "No release notes were published for this version.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        MarkdownView(
                            blocks = blocks,
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            }

            StatusArea(state)
            Actions(state, onDismiss = onDismiss, onOpenSettings = { UpdateManager.openInstallSettings(context) })
        }
    }
}

private fun titleFor(state: UpdateState): String = when (state) {
    is UpdateState.Downloading -> "Downloading update\u2026"
    is UpdateState.Ready -> "Ready to install"
    is UpdateState.NeedsPermission -> "One more step"
    is UpdateState.Installing -> "Installing\u2026"
    is UpdateState.Failed -> "Update failed"
    else -> "Update available"
}

private fun metaLine(u: UpdateInfo): String = listOfNotNull(
    u.assetSize.takeIf { it > 0 }?.let { Formatting.bytes(it) },
    u.abiLabel,
    ReleaseDates.format(u.publishedAt)?.let { "Released $it" },
).joinToString(" \u00B7 ")

@Composable
private fun UpdateHero() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale = pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "glow",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(128.dp)) {
        Box(
            modifier = Modifier
                .size(128.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .background(
                    Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.40f), Color.Transparent)),
                    CircleShape,
                ),
        )
        Box(
            modifier = Modifier.size(88.dp).clip(CircleShape).background(Color(0xFF070607)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(CoreR.drawable.logo_mark),
                contentDescription = null,
                modifier = Modifier.padding(18.dp),
            )
        }
    }
}

@Composable
private fun VersionChange(current: String, latest: String) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHighest) {
            Text("v$current", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "to", tint = scheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(20.dp), color = scheme.primary, contentColor = scheme.onPrimary) {
            Text("v$latest", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Surface(shape = RoundedCornerShape(8.dp), color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer) {
            Text("NEW", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun StatusArea(state: UpdateState) {
    val scheme = MaterialTheme.colorScheme
    when (state) {
        is UpdateState.Downloading -> {
            val fraction = if (state.total > 0) (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f) else null
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WavyProgress(progress = fraction)
                Text(
                    if (state.total > 0) "${Formatting.bytes(state.downloaded)} of ${Formatting.bytes(state.total)} \u00B7 ${(fraction ?: 0f).times(100).toInt()}%"
                    else Formatting.bytes(state.downloaded),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        is UpdateState.Installing -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ExpressiveLoading(indicatorSize = 36.dp)
            Text("Waiting for Android to install\u2026 confirm if it asks.", style = MaterialTheme.typography.bodyMedium)
        }
        is UpdateState.NeedsPermission -> NoticeCard(
            "Android needs your permission, once, to let SHARE64 install its own updates. " +
                "Turn on \u201CAllow from this source\u201D, then come back.",
            scheme.secondaryContainer,
            scheme.onSecondaryContainer,
        )
        is UpdateState.Ready -> state.note?.let { NoticeCard(it, scheme.secondaryContainer, scheme.onSecondaryContainer) }
        is UpdateState.Failed -> NoticeCard(state.message, scheme.errorContainer, scheme.onErrorContainer)
        else -> Unit
    }
}

@Composable
private fun NoticeCard(text: String, container: Color, content: Color) {
    Surface(shape = MaterialTheme.shapes.medium, color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Actions(state: UpdateState, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (state) {
            is UpdateState.Available -> {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExpressiveButton(onClick = onDismiss, kind = ButtonKind.Tonal) { Text("Later") }
                    ExpressiveButton(onClick = { UpdateManager.startUpdate() }, modifier = Modifier.weight(1f)) { Text("Update now") }
                }
                ExpressiveButton(onClick = { UpdateManager.skipThisVersion() }, kind = ButtonKind.Text) { Text("Skip this version") }
            }
            is UpdateState.Downloading -> ExpressiveButton(onClick = { UpdateManager.cancelDownload() }, kind = ButtonKind.Tonal, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
            is UpdateState.Ready -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpressiveButton(onClick = onDismiss, kind = ButtonKind.Tonal) { Text("Later") }
                ExpressiveButton(onClick = { UpdateManager.startUpdate() }, modifier = Modifier.weight(1f)) { Text("Install now") }
            }
            is UpdateState.NeedsPermission -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpressiveButton(onClick = onDismiss, kind = ButtonKind.Tonal) { Text("Later") }
                ExpressiveButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Open settings") }
            }
            is UpdateState.Installing -> ExpressiveButton(onClick = onDismiss, kind = ButtonKind.Text) { Text("Hide") }
            is UpdateState.Failed -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExpressiveButton(onClick = onDismiss, kind = ButtonKind.Tonal) { Text("Close") }
                ExpressiveButton(onClick = { UpdateManager.startUpdate() }, modifier = Modifier.weight(1f)) { Text("Try again") }
            }
            else -> Unit
        }
    }
}
