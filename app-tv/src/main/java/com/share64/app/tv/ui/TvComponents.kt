package com.share64.app.tv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.share64.app.core.TransferItem
import com.share64.core.TransferState
import com.share64.network.DeviceType
import com.share64.network.PeerDevice

fun deviceIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.PHONE -> TvIcons.Phone
    DeviceType.TV -> TvIcons.Tv
    DeviceType.OTHER -> TvIcons.Devices
}

/**
 * The D-pad building block. Focusable (and OK/Enter-clickable). When focused it springs up in
 * size, its corners tighten, its colour flips to the focus colour and a ring appears, so the
 * selection is unmistakable from across the room.
 */
@Composable
fun FocusBox(
    onClick: () -> Unit,
    restColor: Color,
    focusColor: Color,
    modifier: Modifier = Modifier,
    restRadius: Dp = 32.dp,
    focusRadius: Dp = 18.dp,
    focusScale: Float = 1.06f,
    enabled: Boolean = true,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) focusScale else 1f,
        animationSpec = expressiveSpring(0.55f, Spring.StiffnessMediumLow),
        label = "focusScale",
    )
    val radius by animateDpAsState(
        targetValue = if (focused) focusRadius else restRadius,
        animationSpec = expressiveSpring(0.6f, Spring.StiffnessMedium),
        label = "focusRadius",
    )
    val color by animateColorAsState(
        targetValue = if (focused) focusColor else restColor,
        animationSpec = tween(180),
        label = "focusColor",
    )
    val shape = RoundedCornerShape(if (radius < 0.dp) 0.dp else radius)
    val ring = MaterialTheme.colorScheme.onBackground
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focused) Modifier.border(3.dp, ring, shape) else Modifier)
            .clip(shape)
            .background(color)
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
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    FocusBox(
        onClick = onClick,
        modifier = modifier,
        restColor = when {
            !enabled -> scheme.surfaceVariant
            primary -> scheme.primaryContainer
            else -> scheme.surfaceContainerHigh
        },
        focusColor = scheme.primary,
        restRadius = 40.dp,
        focusRadius = 16.dp,
        focusScale = 1.08f,
        enabled = enabled,
    ) { focused ->
        val fg = when {
            !enabled -> scheme.outline
            focused -> scheme.onPrimary
            primary -> scheme.onPrimaryContainer
            else -> scheme.onSurface
        }
        Row(
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
            Text(text, color = fg, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun OnlinePill(online: Boolean, textColor: Color, dotColor: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(textColor.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (online) dotColor else textColor.copy(alpha = 0.4f)),
        )
        Text(if (online) "Online" else "Offline", style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

/** A device as a colour-blocked tile; on focus it turns bright and its cookie starts to spin. */
@Composable
fun DeviceTile(peer: PeerDevice, onClick: () -> Unit, modifier: Modifier = Modifier, index: Int = 0) {
    val p = cardPalette(index)
    FocusBox(
        onClick = onClick,
        modifier = modifier.width(268.dp).height(160.dp),
        restColor = p.container,
        focusColor = p.accent,
        restRadius = 36.dp,
        focusRadius = 20.dp,
    ) { focused ->
        val fg = if (focused) p.onAccent else p.onContainer
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                CookieBadge(
                    size = 60.dp,
                    color = if (focused) p.container else p.accent,
                    lobes = 8 + index % 3,
                    amplitude = 0.08f,
                    spin = focused,
                ) {
                    Icon(
                        deviceIcon(peer.type),
                        contentDescription = null,
                        tint = if (focused) p.onContainer else p.onAccent,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                OnlinePill(peer.reachable, textColor = fg, dotColor = fg)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(peer.displayName, style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (peer.reachable) peer.host else "Not responding",
                    style = MaterialTheme.typography.bodyMedium,
                    color = fg.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ActionTile(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    FocusBox(
        onClick = onClick,
        modifier = modifier.width(268.dp).height(160.dp),
        restColor = scheme.surfaceContainerHigh,
        focusColor = scheme.primary,
        restRadius = 36.dp,
        focusRadius = 20.dp,
    ) { focused ->
        val fg = if (focused) scheme.onPrimary else scheme.onSurface
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            CookieBadge(
                size = 60.dp,
                color = if (focused) scheme.primaryContainer else scheme.secondaryContainer,
                lobes = 9,
                amplitude = 0.08f,
                spin = focused,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = if (focused) scheme.onPrimaryContainer else scheme.onSecondaryContainer,
                    modifier = Modifier.size(30.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = fg)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, count: Int? = null) {
    Row(
        modifier = modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        if (count != null && count > 0) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
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
    val finished = item.state == TransferState.COMPLETED
    val showProgress = item.state == TransferState.ACTIVE || item.state == TransferState.RETRYING ||
        item.state == TransferState.INTERRUPTED

    val container = when {
        failed -> scheme.errorContainer
        finished -> scheme.surfaceContainerHigh
        item.isSend -> scheme.primaryContainer
        else -> scheme.tertiaryContainer
    }
    val onContainer = when {
        failed -> scheme.onErrorContainer
        finished -> scheme.onSurface
        item.isSend -> scheme.onPrimaryContainer
        else -> scheme.onTertiaryContainer
    }
    val accent = when {
        failed -> scheme.error
        finished -> scheme.secondary
        item.isSend -> scheme.primary
        else -> scheme.tertiary
    }
    val onAccent = when {
        failed -> scheme.onError
        finished -> scheme.onSecondary
        item.isSend -> scheme.onPrimary
        else -> scheme.onTertiary
    }

    Surface(shape = RoundedCornerShape(36.dp), color = container, contentColor = onContainer, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CookieBadge(size = 56.dp, color = accent, lobes = 7, amplitude = 0.09f, spin = showProgress) {
                    Icon(
                        imageVector = if (item.isSend) TvIcons.ArrowUp else TvIcons.ArrowDown,
                        contentDescription = if (item.isSend) "Sending" else "Receiving",
                        tint = onAccent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.status, style = MaterialTheme.typography.bodyLarge, color = onContainer.copy(alpha = 0.8f))
                }
                if (showProgress && !item.indeterminate) {
                    Text("${(item.progress * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall)
                }
            }
            if (showProgress) {
                TvWavyProgress(
                    progress = if (item.indeterminate) null else item.progress,
                    color = accent,
                    trackColor = accent.copy(alpha = 0.25f),
                )
            }
            if (item.detail.isNotEmpty()) {
                Text(item.detail, style = MaterialTheme.typography.bodyMedium, color = onContainer.copy(alpha = 0.72f))
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

/** A pill switch whose thumb grows and springs across when it turns on. */
@Composable
private fun TvSwitch(checked: Boolean, focused: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val thumbSize by animateDpAsState(
        targetValue = if (checked) 28.dp else 20.dp,
        animationSpec = expressiveSpring(0.5f, Spring.StiffnessMedium),
        label = "thumbSize",
    )
    val thumbX by animateDpAsState(
        targetValue = if (checked) 35.dp else 9.dp,
        animationSpec = expressiveSpring(0.5f, Spring.StiffnessMedium),
        label = "thumbX",
    )
    val track = when {
        checked -> scheme.tertiary
        focused -> scheme.onPrimary.copy(alpha = 0.35f)
        else -> scheme.outline.copy(alpha = 0.6f)
    }
    val thumb = when {
        checked -> scheme.onTertiary
        focused -> scheme.onPrimary
        else -> scheme.onSurfaceVariant
    }
    Box(modifier = Modifier.size(68.dp, 38.dp).clip(CircleShape).background(track)) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbX)
                .size(thumbSize)
                .clip(CircleShape)
                .background(thumb),
        )
    }
}

@Composable
fun TvToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FocusBox(
        onClick = { onChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        restColor = scheme.surfaceContainerHigh,
        focusColor = scheme.primary,
        restRadius = 28.dp,
        focusRadius = 16.dp,
        focusScale = 1.03f,
    ) { focused ->
        val fg = if (focused) scheme.onPrimary else scheme.onSurface
        val sub = if (focused) scheme.onPrimary.copy(alpha = 0.85f) else scheme.onSurfaceVariant
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = sub)
            }
            Spacer(Modifier.width(24.dp))
            TvSwitch(checked = checked, focused = focused)
        }
    }
}

@Composable
fun TvDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(44.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.width(720.dp),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}
