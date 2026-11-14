package com.share64.app.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.share64.app.core.AppInfo
import com.share64.app.mobile.AboutRepository
import com.share64.app.mobile.Contributor
import com.share64.app.core.R as CoreR

/**
 * About: spinning-cookie logo + version/ABI pills + links, the developer, and GitHub contributors
 * (the contributors list quietly falls back to an invitation card when offline or empty).
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val build = remember { AppInfo.read(context) }
    val contributors by produceState<List<Contributor>?>(initialValue = null) {
        value = AboutRepository.contributors()
    }
    val open: (String) -> Unit = { url -> openUrl(context, url) }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item { ScreenHeader(title = "About", onBack = onBack) }
            item { HeroCard(build, open) }

            item { SectionLabel("DEVELOPER") }
            item { DeveloperCard(open) }

            item { SectionLabel("CONTRIBUTORS") }
            val list = contributors
            when {
                list == null -> item { LoadingRow() }
                list.isEmpty() -> item { OpenSourceCard(open) }
                else -> itemsIndexed(list, key = { _, c -> c.login }) { index, c ->
                    ContributorRow(c, index, list.size, open)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCard(build: AppInfo.BuildInfo, onOpen: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    BouncyCard(
        onClick = null,
        modifier = Modifier.fillMaxWidth(),
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        restRadius = 44.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CookieBadge(size = 132.dp, color = Color(0xFF070607), lobes = 12, amplitude = 0.05f, spin = true) {
                Image(
                    painter = painterResource(CoreR.drawable.logo_mark),
                    contentDescription = "SHARE64 logo",
                    modifier = Modifier.padding(30.dp),
                )
            }
            Text(AppInfo.APP_NAME, style = MaterialTheme.typography.displayMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoPill(build.versionName, scheme.primary, scheme.onPrimary)
                InfoPill(build.abiLabel, scheme.tertiaryContainer, scheme.onTertiaryContainer)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LinkButton("Source code", ShareIcons.Code) { onOpen(AppInfo.REPO_URL) }
                LinkButton("Releases", Icons.Default.Star) { onOpen(AppInfo.RELEASES_URL) }
                LinkButton("Report a bug", Icons.Default.Warning) { onOpen(AppInfo.ISSUES_URL) }
            }
            Text(
                AppInfo.TAGLINE,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InfoPill(text: String, container: Color, content: Color) {
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun LinkButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    ExpressiveButton(onClick = onClick, kind = ButtonKind.Tonal) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun DeveloperCard(onOpen: (String) -> Unit) {
    val p = cardPalette(1)
    BouncyCard(
        onClick = { onOpen(AppInfo.DEVELOPER_URL) },
        modifier = Modifier.fillMaxWidth(),
        color = p.container,
        contentColor = p.onContainer,
        restRadius = 40.dp,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RemoteAvatar(AppInfo.DEVELOPER_AVATAR_URL, AppInfo.DEVELOPER_NAME.take(1), 68.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(AppInfo.DEVELOPER_NAME, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Developer · @${AppInfo.DEVELOPER_GITHUB}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.onContainer.copy(alpha = 0.78f),
                )
            }
            CookieBadge(size = 52.dp, color = p.accent, lobes = 8, amplitude = 0.07f) {
                Icon(ShareIcons.GitHub, contentDescription = "Open GitHub profile", tint = p.onAccent, modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
private fun ContributorRow(c: Contributor, index: Int, count: Int, onOpen: (String) -> Unit) {
    val p = cardPalette(index)
    Surface(
        onClick = { onOpen(c.profileUrl) },
        shape = segmentedShape(index, count),
        color = p.container,
        contentColor = p.onContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RemoteAvatar(c.avatarUrl, c.login.take(1).uppercase(), 52.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(c.login, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (c.contributions == 1) "1 contribution" else "${c.contributions} contributions",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.onContainer.copy(alpha = 0.75f),
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun OpenSourceCard(onOpen: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    BouncyCard(
        onClick = null,
        modifier = Modifier.fillMaxWidth(),
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        restRadius = 40.dp,
    ) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "SHARE64 is a small independent project. Ideas, bug reports and pull requests are welcome on GitHub.",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            ExpressiveButton(onClick = { onOpen(AppInfo.REPO_URL) }, kind = ButtonKind.Tonal) { Text("Open on GitHub") }
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        ExpressiveLoading(indicatorSize = 44.dp)
    }
}

/** Cookie-shaped avatar from the web; shows the initial until (or unless) the image arrives. */
@Composable
private fun RemoteAvatar(url: String, initial: String, avatarSize: Dp) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) { value = AboutRepository.avatar(url) }
    Box(
        modifier = Modifier
            .size(avatarSize)
            .clip(BlobShape(lobes = 10, amplitude = 0.05f))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(initial, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // no browser installed
    }
}
