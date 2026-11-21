package com.share64.app.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.share64.app.core.Formatting
import com.share64.app.core.WebFileInfo
import com.share64.app.mobile.MainViewModel
import com.share64.app.mobile.WebDownloadUi
import kotlin.math.roundToInt

/**
 * Covers the whole app while a "share with anyone" link is being received: connect, wait to be allowed,
 * show what is offered, download with progress, and say where the files went. It sits above every
 * screen, so it works the same whether the link came from the in-app scanner or from the browser button.
 */
@Composable
fun WebDownloadOverlay(vm: MainViewModel) {
    val web by vm.webDownload.collectAsStateWithLifecycle()
    BackHandler(enabled = web !is WebDownloadUi.Idle) { vm.closeWeb() }
    AnimatedContent(
        targetState = web,
        contentKey = { it::class },
        transitionSpec = {
            (fadeIn(tween(220)) + scaleIn(spring(0.8f, Spring.StiffnessMediumLow), initialScale = 0.94f)) togetherWith fadeOut(tween(140))
        },
        label = "webDownload",
    ) { _ ->
        // the content reads the live state, so progress updates do not restart the transition
        val live by vm.webDownload.collectAsStateWithLifecycle()
        if (live !is WebDownloadUi.Idle) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
                    when (val s = live) {
                        is WebDownloadUi.Connecting -> Centered {
                            ExpressiveLoading(indicatorSize = 80.dp)
                            Text("Connecting to the sender…", style = MaterialTheme.typography.titleLarge)
                            Text(s.host, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ExpressiveButton(onClick = vm::closeWeb, kind = ButtonKind.Text) { Text("Cancel") }
                        }
                        is WebDownloadUi.Waiting -> WaitingView(s.sender, vm::closeWeb)
                        is WebDownloadUi.Ready -> ReadyView(s, onDownload = vm::startWebDownload, onCancel = vm::closeWeb)
                        is WebDownloadUi.Downloading -> DownloadingView(s, onCancel = vm::closeWeb)
                        is WebDownloadUi.Done -> DoneView(s, onClose = vm::closeWeb)
                        is WebDownloadUi.Failed -> FailedView(s.title, s.detail, onRetry = vm::retryWeb, onClose = vm::closeWeb)
                        WebDownloadUi.Idle -> Unit
                    }
                }
            }
        } else {
            Spacer(Modifier)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) { content() }
}

@Composable
private fun WaitingView(sender: String, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Centered {
        Box(modifier = Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            PulseRings(color = scheme.primary)
            CookieBadge(size = 96.dp, color = scheme.primary, lobes = 9, amplitude = 0.09f, spin = true) {
                Icon(ShareIcons.Link, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(40.dp))
            }
        }
        Text("Waiting to be allowed", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.popIn(1))
        Text(
            "${sender.ifBlank { "The sender" }} sees an Allow button on their phone. Your files appear here as soon as they tap it.",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp).popIn(2),
        )
        ExpressiveButton(onClick = onCancel, kind = ButtonKind.Tonal) { Text("Cancel") }
    }
}

@Composable
private fun ReadyView(s: WebDownloadUi.Ready, onDownload: () -> Unit, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val total = s.files.sumOf { maxOf(it.size, 0L) }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("INCOMING", style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.popIn(0))
        Text(
            "${s.sender.ifBlank { "Someone" }} is sharing ${s.files.size} file${if (s.files.size == 1) "" else "s"}",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.popIn(1),
        )
        if (total > 0) Text(Formatting.bytes(total), style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(s.files, key = { _, f -> f.id }) { i, f ->
                FileRow(f, i, s.files.size, state = FileState.Waiting)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ExpressiveButton(onClick = onCancel, kind = ButtonKind.Tonal) { Text("Cancel") }
            ExpressiveButton(onClick = onDownload, modifier = Modifier.weight(1f)) {
                Text(if (total > 0) "Download all (${Formatting.bytes(total)})" else "Download all")
            }
        }
    }
}

private enum class FileState { Waiting, Active, Done }

@Composable
private fun FileRow(f: WebFileInfo, index: Int, count: Int, state: FileState) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = segmentedShape(index, count),
        color = if (state == FileState.Done) scheme.tertiaryContainer else scheme.surfaceContainerHigh,
        contentColor = if (state == FileState.Done) scheme.onTertiaryContainer else scheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (f.size >= 0) Text(Formatting.bytes(f.size), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.7f))
            }
            when (state) {
                FileState.Done -> Icon(Icons.Default.Check, contentDescription = "Saved", modifier = Modifier.size(22.dp))
                FileState.Active -> ExpressiveLoading(indicatorSize = 26.dp)
                FileState.Waiting -> Unit
            }
        }
    }
}

@Composable
private fun DownloadingView(s: WebDownloadUi.Downloading, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fraction = if (s.totalBytes > 0) (s.doneBytes.toFloat() / s.totalBytes).coerceIn(0f, 1f) else null
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("DOWNLOADING", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
        Text(
            fraction?.let { "${(it * 100).toInt()}%" } ?: "…",
            style = MaterialTheme.typography.displayMedium,
        )
        WavyProgress(progress = fraction, strokeWidth = 8.dp)
        Text(
            "File ${minOf(s.index + 1, s.files.size)} of ${s.files.size}" +
                if (s.totalBytes > 0) " · ${Formatting.bytes(s.doneBytes)} of ${Formatting.bytes(s.totalBytes)}" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            itemsIndexed(s.files, key = { _, f -> f.id }) { i, f ->
                FileRow(f, i, s.files.size, state = when {
                    i < s.index -> FileState.Done
                    i == s.index -> FileState.Active
                    else -> FileState.Waiting
                })
            }
        }
        ExpressiveButton(onClick = onCancel, kind = ButtonKind.Tonal, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
private fun DoneView(s: WebDownloadUi.Done, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val where = s.saved.map { it.location }.distinct().joinToString(" and ")
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        PairedBurst(cookie = scheme.primary, onCookie = scheme.onPrimary, dots = listOf(scheme.primary, scheme.tertiary, scheme.secondary))
        Text("SAVED", style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.popIn(2))
        Text(
            "${s.saved.size} file${if (s.saved.size == 1) "" else "s"} from ${s.sender.ifBlank { "the sender" }}",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.popIn(3),
        )
        if (where.isNotBlank()) {
            Text(where, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, modifier = Modifier.popIn(4))
        }
        Spacer(Modifier.height(18.dp))
        ExpressiveButton(onClick = onClose, modifier = Modifier.popIn(5)) { Text("Done") }
    }
}

@Composable
private fun FailedView(title: String, detail: String, onRetry: () -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shake = remember { Animatable(0f) }
    LaunchedEffect(title, detail) {
        repeat(3) {
            shake.animateTo(14f, tween(55))
            shake.animateTo(-14f, tween(55))
        }
        shake.animateTo(0f, spring(0.4f, Spring.StiffnessMedium))
    }
    Centered {
        Surface(
            shape = RoundedCornerShape(40.dp),
            color = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
            modifier = Modifier.offset { IntOffset(shake.value.roundToInt(), 0) },
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CookieBadge(size = 60.dp, color = scheme.error, lobes = 8, amplitude = 0.08f) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = scheme.onError, modifier = Modifier.size(30.dp))
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExpressiveButton(onClick = onClose, kind = ButtonKind.Tonal) { Text("Close") }
                    ExpressiveButton(onClick = onRetry) { Text("Try again") }
                }
            }
        }
    }
}
