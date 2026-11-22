package com.share64.app.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.share64.app.core.BrowserEntry
import com.share64.app.core.BrowserRoot
import com.share64.app.core.FileBrowser
import com.share64.app.core.FolderListing
import com.share64.app.core.Formatting
import com.share64.app.core.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A file browser made for a remote control. Many TVs and Fire TV boxes ship without a system file
 * picker, so SHARE64 brings its own: pick a storage, open folders with OK, tick files with OK, then
 * move right to the panel and press the big button. Back goes up one folder.
 *
 * [onConfirm] gets the ticked files (in the order they were ticked).
 */
@Composable
fun TvFilePicker(
    title: String,
    destination: String,
    confirmLabel: (count: Int) -> String,
    onConfirm: (List<File>) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val roots = remember { StorageAccess.roots(context) }
    val volumes = remember(roots) { roots.filter { !it.shortcut } }
    val shortcuts = remember(roots) { roots.filter { it.shortcut } }

    var dir by remember { mutableStateOf<File?>(null) } // null = the list of storages
    var loaded by remember { mutableStateOf<Pair<File, FolderListing>?>(null) }
    var cameFrom by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    val selectedPaths = remember(selected) { selected.map { it.path }.toHashSet() }

    LaunchedEffect(dir) {
        val d = dir
        if (d == null) {
            loaded = null
        } else {
            val result = withContext(Dispatchers.IO) { FileBrowser.list(d) }
            loaded = d to result
        }
    }
    val listing: FolderListing? = loaded?.takeIf { it.first == dir }?.second

    fun goUp(): Boolean {
        val d = dir ?: return false
        cameFrom = d.absolutePath
        dir = FileBrowser.parent(d, roots)
        return true
    }

    fun open(folder: File) {
        cameFrom = null
        dir = folder
    }

    fun toggle(e: BrowserEntry) {
        selected = if (e.path in selectedPaths) selected.filterNot { it.path == e.path } else selected + e
    }

    val entries: List<BrowserEntry> = (listing as? FolderListing.Ok)?.entries.orEmpty()
    val selectableHere = entries.filter { !it.isDirectory && it.readable }
    val allHereSelected = selectableHere.isNotEmpty() && selectableHere.all { it.path in selectedPaths }

    // Which row gets the remote's focus when a screen opens: the folder we just came out of, else the first item.
    val focusIndex = when {
        dir == null -> 0
        else -> {
            val back = cameFrom?.let { path -> entries.indexOfFirst { it.path == path } } ?: -1
            when {
                back >= 0 -> back + 1
                entries.isNotEmpty() -> 1
                else -> 0
            }
        }
    }
    val initialFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(dir, listing) {
        if (dir != null && listing == null) return@LaunchedEffect // still loading
        listState.scrollToItem((focusIndex - 2).coerceAtLeast(0))
        delay(90)
        runCatching { initialFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = { if (!goUp()) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                // ---------------------------------------------------------------- the browser
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = dir?.let { FileBrowser.label(it, roots) } ?: "Choose where your files are",
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(14.dp))

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        val current = dir
                        if (current == null) {
                            if (roots.isEmpty()) {
                                item(key = "none") { Note("No storage found on this TV.") }
                            }
                            itemsIndexed(volumes, key = { _, r -> "v:" + r.dir.absolutePath }) { index, r ->
                                PickerRow(
                                    icon = TvIcons.Storage,
                                    title = r.label,
                                    subtitle = if (r.removable) "Removable drive" else r.dir.absolutePath,
                                    onClick = { open(r.dir) },
                                    modifier = if (index == 0) Modifier.focusRequester(initialFocus) else Modifier,
                                )
                            }
                            if (shortcuts.isNotEmpty()) {
                                item(key = "shortcuts") {
                                    Text(
                                        "Shortcuts",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = scheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp, top = 10.dp),
                                    )
                                }
                                itemsIndexed(shortcuts, key = { _, r -> "s:" + r.dir.absolutePath }) { index, r ->
                                    PickerRow(
                                        icon = TvIcons.Folder,
                                        title = r.label,
                                        subtitle = null,
                                        onClick = { open(r.dir) },
                                        modifier = if (volumes.isEmpty() && index == 0) Modifier.focusRequester(initialFocus) else Modifier,
                                    )
                                }
                            }
                        } else {
                            val parent = FileBrowser.parent(current, roots)
                            item(key = "up") {
                                PickerRow(
                                    icon = TvIcons.ArrowBack,
                                    title = "Up",
                                    subtitle = if (parent != null) FileBrowser.label(parent, roots) else "All storages",
                                    onClick = { goUp() },
                                    modifier = if (focusIndex == 0) Modifier.focusRequester(initialFocus) else Modifier,
                                )
                            }
                            when {
                                listing == null -> item(key = "loading") { Note("Loading\u2026") }
                                listing is FolderListing.Unreadable -> item(key = "unreadable") {
                                    Note("This folder can't be opened. SHARE64 may not be allowed to read it.")
                                }
                                entries.isEmpty() -> item(key = "empty") { Note("This folder is empty.") }
                            }
                            itemsIndexed(entries, key = { _, e -> e.path }) { position, e ->
                                val index = position + 1
                                val ticked = e.path in selectedPaths
                                PickerRow(
                                    icon = if (e.isDirectory) TvIcons.Folder else TvIcons.File,
                                    title = e.name,
                                    subtitle = when {
                                        e.isDirectory -> "Folder"
                                        !e.readable -> "Can't be read"
                                        else -> Formatting.bytes(e.size)
                                    },
                                    onClick = { if (e.isDirectory) open(e.file) else toggle(e) },
                                    tick = if (e.isDirectory) null else ticked,
                                    enabled = e.isDirectory || e.readable,
                                    modifier = if (index == focusIndex) Modifier.focusRequester(initialFocus) else Modifier,
                                )
                            }
                        }
                    }
                }

                // ---------------------------------------------------------------- the panel on the right
                Column(
                    modifier = Modifier.width(330.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(shape = RoundedCornerShape(32.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("To", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                            Text(destination, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                if (selected.isEmpty()) "No files ticked yet" else "${selected.size} file${if (selected.size == 1) "" else "s"} ticked",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (selected.isNotEmpty()) {
                                Text(
                                    Formatting.bytes(selected.sumOf { it.size }),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    TvButton(
                        confirmLabel(selected.size),
                        onClick = { onConfirm(selected.map { it.file }) },
                        primary = true,
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TvButton(
                        if (allHereSelected) "Untick files here" else "Tick all files here",
                        onClick = {
                            selected = if (allHereSelected) {
                                val here = selectableHere.map { it.path }.toHashSet()
                                selected.filterNot { it.path in here }
                            } else {
                                selected + selectableHere.filter { it.path !in selectedPaths }
                            }
                        },
                        enabled = selectableHere.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TvButton("Clear ticks", onClick = { selected = emptyList() }, modifier = Modifier.fillMaxWidth())
                    TvButton("Cancel", onClick = onClose, modifier = Modifier.fillMaxWidth())
                    Text(
                        "OK opens a folder or ticks a file. Back goes up. Press Right to reach these buttons.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}

/** One row of the browser: a storage, a folder, the "Up" row or a file (with a tick box). */
@Composable
private fun PickerRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** null = no tick box (folders); true / false = a file that is / is not ticked. */
    tick: Boolean? = null,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    FocusBox(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        restColor = if (tick == true) scheme.primaryContainer else scheme.surfaceContainerHigh,
        focusColor = scheme.primary,
        restRadius = 26.dp,
        focusRadius = 14.dp,
        focusScale = 1.02f,
        enabled = enabled,
    ) { focused ->
        val fg = when {
            !enabled -> scheme.outline
            focused -> scheme.onPrimary
            tick == true -> scheme.onPrimaryContainer
            else -> scheme.onSurface
        }
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(30.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = fg, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = fg.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (tick != null) {
                Icon(
                    if (tick) TvIcons.CheckBox else TvIcons.CheckBoxBlank,
                    contentDescription = if (tick) "Ticked" else "Not ticked",
                    tint = fg,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/**
 * Shown when SHARE64 may not read this TV's storage yet. "Allow" opens the system screen (Android 11+)
 * or asks the normal permission question (older versions); the system picker is offered as a way out
 * for the TVs that have one.
 */
@Composable
fun TvStorageAccessDialog(
    onAllow: () -> Unit,
    onSystemPicker: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val allowFocus = remember { FocusRequester() }
    TvDialog(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            CookieBadge(size = 72.dp, color = scheme.primary, lobes = 9, amplitude = 0.09f, spin = true) {
                Icon(TvIcons.Folder, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(34.dp))
            }
            Text("Allow access to files", style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            if (StorageAccess.needsSettingsScreen) {
                "To choose files on this TV, SHARE64 needs \u201CAll files access\u201D. Choose Allow, switch it on for SHARE64, then press Back. " +
                    "If SHARE64 restarts, pick your files again."
            } else {
                "To choose files on this TV, SHARE64 needs permission to read its storage. Choose Allow and answer the question that appears."
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "SHARE64 only reads the files you tick. Nothing leaves your network.",
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvButton("Allow", onClick = onAllow, primary = true, modifier = Modifier.focusRequester(allowFocus))
            TvButton("Use system picker", onClick = onSystemPicker)
            TvButton("Cancel", onClick = onDismiss)
        }
        LaunchedEffect(Unit) {
            delay(150)
            runCatching { allowFocus.requestFocus() }
        }
    }
}
