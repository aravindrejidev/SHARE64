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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
 * About: logo + version/ABI chips + links, the developer, and GitHub contributors
 * (the contributors list quietly falls back to an invitation card when offline or empty).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val build = remember { AppInfo.read(context) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val contributors by produceState<List<Contributor>?>(initialValue = null) {
        value = AboutRepository.contributors()
    }
    val open: (String) -> Unit = { url -> openUrl(context, url) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
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
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .background(Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.45f), Color.Transparent)))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(104.dp).clip(CircleShape).background(Color(0xFF070607)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(CoreR.drawable.logo_mark),
                    contentDescription = "SHARE64 logo",
                    modifier = Modifier.padding(20.dp),
                )
            }
            Text(AppInfo.APP_NAME, style = MaterialTheme.typography.displaySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoChip(build.versionName)
                InfoChip(build.abiLabel)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
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
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
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
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RemoteAvatar(AppInfo.DEVELOPER_AVATAR_URL, AppInfo.DEVELOPER_NAME.take(1), 64.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(AppInfo.DEVELOPER_NAME, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Developer · @${AppInfo.DEVELOPER_GITHUB}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = { onOpen(AppInfo.DEVELOPER_URL) }, modifier = Modifier.size(52.dp)) {
                Icon(ShareIcons.GitHub, contentDescription = "Open GitHub profile")
            }
        }
    }
}

@Composable
private fun ContributorRow(c: Contributor, index: Int, count: Int, onOpen: (String) -> Unit) {
    Surface(
        onClick = { onOpen(c.profileUrl) },
        shape = segmentedShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RemoteAvatar(c.avatarUrl, c.login.take(1).uppercase(), 48.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(c.login, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (c.contributions == 1) "1 contribution" else "${c.contributions} contributions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OpenSourceCard(onOpen: (String) -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "SHARE64 is a small independent project. Ideas, bug reports and pull requests are welcome on GitHub.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ExpressiveButton(onClick = { onOpen(AppInfo.REPO_URL) }, kind = ButtonKind.Tonal) { Text("Open on GitHub") }
        }
    }
}

@Composable
private fun LoadingRow() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        ExpressiveLoading(indicatorSize = 36.dp)
    }
}

/** Round avatar from the web; shows the initial until (or unless) the image arrives. */
@Composable
private fun RemoteAvatar(url: String, initial: String, avatarSize: Dp) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) { value = AboutRepository.avatar(url) }
    Box(
        modifier = Modifier.size(avatarSize).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(initial, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
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
