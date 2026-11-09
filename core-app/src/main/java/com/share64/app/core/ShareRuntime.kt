package com.share64.app.core

import android.net.Uri
import com.share64.network.Share64Node
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide handle to the running node. [ShareService] owns the node; UIs observe it here
 * and keep working while it starts, restarts or stops.
 */
object ShareRuntime {
    private val _node = MutableStateFlow<Share64Node?>(null)
    val node: StateFlow<Share64Node?> = _node.asStateFlow()

    private val _uiVisible = MutableStateFlow(false)
    /** True while an Activity of the app is started. Drives notification and auto-stop policy. */
    val uiVisible: StateFlow<Boolean> = _uiVisible.asStateFlow()

    private val _received = MutableStateFlow<Map<Long, Uri>>(emptyMap())
    /** Transfer id -> content Uri of the finished file (only when it was exported to public storage). */
    val receivedUris: StateFlow<Map<Long, Uri>> = _received.asStateFlow()

    private val _locations = MutableStateFlow<Map<Long, String>>(emptyMap())
    /** Transfer id -> where the finished file was saved, e.g. "Downloads/SHARE64". */
    val receivedLocations: StateFlow<Map<Long, String>> = _locations.asStateFlow()

    internal fun setNode(node: Share64Node?) { _node.value = node }

    fun setUiVisible(visible: Boolean) { _uiVisible.value = visible }

    /** [uri] is null when the file stayed in app storage. */
    internal fun markReceived(transferId: Long, uri: Uri?, location: String) {
        if (uri != null) _received.update { it + (transferId to uri) }
        _locations.update { it + (transferId to location) }
    }
}
