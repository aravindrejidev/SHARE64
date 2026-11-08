package com.share64.app.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.share64.app.core.TransferItem
import com.share64.core.TransferState
import com.share64.network.DeviceType
import com.share64.network.PeerDevice

/**
 * Basic D-pad building block: focusable (and OK/Enter-clickable), scales up and gets a thick ring
 * when focused so it is readable from across the room.
 */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    enabled: Boolean = true,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "focusScale")
    val ring = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .scale(scale)
            .then(if (focused) Modifier.border(3.dp, ring, shape) else Modifier)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
    ) {
        content(focused)
    }
}

@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    FocusBox(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp), enabled = enabled) { focused ->
        val scheme = MaterialTheme.colorScheme
        val bg = when {
            !enabled -> scheme.surfaceVariant
            focused -> scheme.primary
            primary -> scheme.primaryContainer
            else -> scheme.surfaceVariant
        }
        val fg = when {
            !enabled -> scheme.outline
            focused -> scheme.onPrimary
            primary -> scheme.onPrimaryContainer
            else -> scheme.onSurfaceVariant
        }
        Box(modifier = Modifier.background(bg).padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun TvAvatar(type: DeviceType, name: String, size: androidx.compose.ui.unit.Dp = 56.dp) {
    val label = if (type == DeviceType.TV) "TV" else name.trim().take(1).uppercase().ifEmpty { "?" }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DeviceTile(peer: PeerDevice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusBox(onClick = onClick, modifier = modifier.width(260.dp)) { focused ->
        val scheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .background(if (focused) scheme.primaryContainer else scheme.surfaceVariant)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvAvatar(peer.type, peer.displayName)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(if (peer.reachable) scheme.tertiary else scheme.outline),
                )
            }
            Text(
                peer.displayName,
                style = MaterialTheme.typography.titleLarge,
                color = if (focused) scheme.onPrimaryContainer else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (peer.reachable) peer.host else "Not responding",
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ActionTile(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusBox(onClick = onClick, modifier = modifier.width(260.dp)) { focused ->
        val scheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .background(if (focused) scheme.primaryContainer else scheme.surface)
                .border(1.dp, scheme.outline, RoundedCornerShape(16.dp))
                .padding(20.dp)
                .height(112.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = if (focused) scheme.onPrimaryContainer else scheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (focused) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

@Composable
fun TvTransferRow(
    item: TransferItem,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val failed = item.state == TransferState.FAILED
    Surface(shape = RoundedCornerShape(16.dp), color = scheme.surface, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.status,
                style = MaterialTheme.typography.bodyLarge,
                color = if (failed) scheme.error else scheme.onSurfaceVariant,
            )
            val showProgress = item.state == TransferState.ACTIVE || item.state == TransferState.RETRYING ||
                item.state == TransferState.INTERRUPTED
            if (showProgress) {
                if (item.indeterminate) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
                } else {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
                }
            }
            if (item.detail.isNotEmpty()) {
                Text(item.detail, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            if (item.canCancel || item.canOpen || item.canDismiss) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (item.canOpen) TvButton("Open", onOpen, primary = true)
                    if (item.canCancel) TvButton("Cancel", onCancel)
                    if (item.canDismiss) TvButton("Dismiss", onDismiss)
                }
            }
        }
    }
}

@Composable
fun TvToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FocusBox(onClick = { onChange(!checked) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { focused ->
        val scheme = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .background(if (focused) scheme.primaryContainer else scheme.surfaceVariant)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (focused) scheme.onPrimaryContainer else scheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = if (focused) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(20.dp))
            Text(
                if (checked) "ON" else "OFF",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (checked) scheme.tertiary else scheme.outline,
            )
        }
    }
}

@Composable
fun TvDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.width(720.dp),
        ) {
            Column(modifier = Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}
