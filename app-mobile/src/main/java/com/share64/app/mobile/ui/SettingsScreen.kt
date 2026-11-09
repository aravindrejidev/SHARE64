package com.share64.app.mobile.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.share64.app.core.PortRules
import com.share64.app.core.ThemeMode
import com.share64.app.mobile.MainViewModel
import com.share64.network.PeerTxt

/** Full-screen settings, grouped into segmented lists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val ui by vm.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var editName by remember { mutableStateOf(false) }
    var editPort by remember { mutableStateOf(false) }
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setDownloadFolder(uri)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
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
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // ---- Device -------------------------------------------------------------
            item { SectionLabel("Device") }
            item {
                SettingRow(
                    index = 0, count = 1,
                    icon = Icons.Default.Person,
                    title = "Device name",
                    subtitle = settings.deviceName,
                    onClick = { editName = true },
                )
            }

            // ---- Network ------------------------------------------------------------
            item { SectionLabel("Network") }
            item {
                val active = ui.port
                SettingRow(
                    index = 0, count = 4,
                    icon = ShareIcons.Lan,
                    title = "Port",
                    subtitle = if (active > 0 && active != settings.port) "${settings.port} (in use: $active)" else settings.port.toString(),
                    onClick = { editPort = true },
                )
            }
            item {
                SettingRow(
                    index = 1, count = 4,
                    icon = Icons.Default.Settings,
                    title = "Keep receiving in background",
                    subtitle = "Stay visible to other devices while SHARE64 is closed",
                    onClick = { vm.setReceiveInBackground(!settings.receiveInBackground) },
                    trailing = { Switch(checked = settings.receiveInBackground, onCheckedChange = null) },
                )
            }
            item {
                SettingRow(
                    index = 2, count = 4,
                    icon = Icons.Default.Settings,
                    title = "Accept files automatically",
                    subtitle = "Anyone on your Wi-Fi can send you files without asking. Leave off unless you trust the network.",
                    onClick = { vm.setAutoAccept(!settings.autoAccept) },
                    trailing = { Switch(checked = settings.autoAccept, onCheckedChange = null) },
                )
            }
            item {
                SettingRow(
                    index = 3, count = 4,
                    icon = ShareIcons.Lan,
                    title = "Your address",
                    subtitle = if (ui.addresses.isEmpty()) "Not connected to a network"
                    else ui.addresses.joinToString("\n") { if (ui.port > 0) "$it:${ui.port}" else it },
                )
            }

            // ---- Storage ------------------------------------------------------------
            item { SectionLabel("Storage") }
            val folderRows = if (settings.customDownloadFolder) 2 else 1
            item {
                SettingRow(
                    index = 0, count = folderRows,
                    icon = ShareIcons.Folder,
                    title = "Download folder",
                    subtitle = settings.downloadLabel,
                    onClick = { folderPicker.launch(null) },
                )
            }
            if (settings.customDownloadFolder) {
                item {
                    SettingRow(
                        index = 1, count = 2,
                        icon = Icons.Default.Refresh,
                        title = "Use the default folder",
                        subtitle = "Downloads/SHARE64",
                        onClick = { vm.setDownloadFolder(null) },
                    )
                }
            }

            // ---- Appearance ---------------------------------------------------------
            item { SectionLabel("Appearance") }
            item {
                SettingRow(
                    index = 0, count = 3,
                    icon = ShareIcons.DarkMode,
                    title = "Theme",
                    content = {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            val modes = ThemeMode.entries
                            modes.forEachIndexed { i, mode ->
                                SegmentedButton(
                                    selected = settings.themeMode == mode,
                                    onClick = { vm.setThemeMode(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                                ) {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "System"
                                            ThemeMode.LIGHT -> "Light"
                                            ThemeMode.DARK -> "Dark"
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
            item {
                SettingRow(
                    index = 1, count = 3,
                    icon = ShareIcons.Palette,
                    title = "Dynamic color",
                    subtitle = if (dynamicSupported) "Use colors from your wallpaper (Material You)" else "Needs Android 12 or newer",
                    enabled = dynamicSupported,
                    onClick = { vm.setDynamicColor(!settings.dynamicColor) },
                    trailing = { Switch(checked = settings.dynamicColor && dynamicSupported, onCheckedChange = null, enabled = dynamicSupported) },
                )
            }
            item {
                SettingRow(
                    index = 2, count = 3,
                    icon = ShareIcons.DarkMode,
                    title = "AMOLED dark mode",
                    subtitle = "Pure black backgrounds in dark theme. Saves battery on OLED screens.",
                    onClick = { vm.setAmoled(!settings.amoled) },
                    trailing = { Switch(checked = settings.amoled, onCheckedChange = null) },
                )
            }
        }
    }

    if (editName) {
        TextInputDialog(
            title = "Device name",
            label = "Name",
            initial = settings.deviceName,
            supporting = "This is how other devices see you. Sharing restarts when you change it.",
            maxLength = PeerTxt.MAX_NAME_CHARS,
            validate = { if (it.isBlank()) "Name can't be empty" else null },
            onConfirm = {
                vm.setDeviceName(it)
                editName = false
            },
            onDismiss = { editName = false },
        )
    }

    if (editPort) {
        TextInputDialog(
            title = "Receiver port",
            label = "Port",
            initial = settings.port.toString(),
            supporting = "Devices find each other automatically; you only need this for manual connections. " +
                "Sharing restarts when you change it.",
            keyboardType = KeyboardType.Number,
            maxLength = 5,
            neutralLabel = "Default",
            onNeutral = {
                vm.setPort(PortRules.DEFAULT)
                editPort = false
            },
            validate = { PortRules.error(it) },
            onConfirm = {
                PortRules.parse(it)?.let(vm::setPort)
                editPort = false
            },
            onDismiss = { editPort = false },
        )
    }
}

/** One row of a segmented settings group. [content] sits under the text (e.g. a button row). */
@Composable
private fun SettingRow(
    index: Int,
    count: Int,
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = segmentedShape(index, count)
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (enabled) scheme.primaryContainer else scheme.surfaceVariant,
                    modifier = Modifier.size(44.dp),
                ) {
                    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (enabled) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f),
                    )
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                }
                trailing?.invoke()
            }
            content?.invoke()
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            color = scheme.surfaceContainer,
            modifier = modifier.fillMaxWidth(),
            content = body,
        )
    } else {
        Surface(shape = shape, color = scheme.surfaceContainer, modifier = modifier.fillMaxWidth(), content = body)
    }
}
