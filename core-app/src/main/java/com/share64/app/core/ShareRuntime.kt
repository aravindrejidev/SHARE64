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
    /** Transfer id -> content Uri of the finished file in the public Downloads folder. */
    val receivedUris: StateFlow<Map<Long, Uri>> = _received.asStateFlow()

    internal fun setNode(node: Share64Node?) { _node.value = node }

    fun setUiVisible(visible: Boolean) { _uiVisible.value = visible }

    internal fun markReceived(transferId: Long, uri: Uri) {
        _received.update { it + (transferId to uri) }
    }
}
