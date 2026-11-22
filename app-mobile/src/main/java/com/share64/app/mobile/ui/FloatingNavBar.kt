package com.share64.app.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The two main screens: pick files and a device (Send), or answer what others want to send (Receive). */
enum class MainTab { Send, Receive }

/**
 * A pill that floats above the content at the bottom of the screen: Send first, then Receive. The
 * selected side is solid and springs under the finger; [receiveBadge] counts devices waiting for an answer.
 */
@Composable
fun FloatingNavBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    receiveBadge: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 36.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = scheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
        ) {
            Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NavItem(
                    label = "Send",
                    icon = ShareIcons.Upload,
                    selected = selected == MainTab.Send,
                    badge = 0,
                    onClick = { onSelect(MainTab.Send) },
                )
                NavItem(
                    label = "Receive",
                    icon = ShareIcons.Download,
                    selected = selected == MainTab.Receive,
                    badge = receiveBadge,
                    onClick = { onSelect(MainTab.Receive) },
                )
            }
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, badge: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        targetValue = if (selected) scheme.primary else Color.Transparent,
        animationSpec = tween(240),
        label = "navContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
        animationSpec = tween(240),
        label = "navContent",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(30.dp),
        color = container,
        contentColor = content,
        interactionSource = interaction,
        modifier = Modifier.pressScale(interaction, 0.95f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (badge > 0) {
                Surface(shape = CircleShape, color = scheme.tertiary, contentColor = scheme.onTertiary) {
                    Text(
                        text = badge.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
