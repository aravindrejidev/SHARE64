package com.share64.app.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.share64.app.core.TransferItem
import com.share64.app.mobile.IncomingOffer
import com.share64.app.mobile.MainUiState
import com.share64.app.mobile.MainViewModel
import com.share64.network.DiscoveryStatus
import com.share64.core.TransferState

/**
 * The Receive tab: the name other devices see, a button to scan a QR code, and the devices that are
 * waiting for you to say yes to a file. Files that are coming in, or arrived, are listed below them.
 */
@Composable
fun ReceiveScreen(
    vm: MainViewModel,
    state: MainUiState,
    padding: PaddingValues,
    onOpenSettings: () -> Unit,
    onOpenScan: () -> Unit,
    onSharingChange: (Boolean) -> Unit,
    onTurnOnSharing: () -> Unit,
) {
    val context = LocalContext.current
    val incoming: List<TransferItem> = state.transfers.filter { !it.isSend && it.state != TransferState.OFFERED }
    LazyVerticalGrid(
        columns = GridCells.Fixed(1),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            MainHeader(onOpenSettings)
        }

        if (needsNetworkNotice(state) && state.sharingEnabled) {
            item(key = "notice") {
                NetworkNotice(state, onTurnOnSharing)
            }
        }

        item(key = "name") {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                SectionLabel("You'll appear as")
                NameCard(name = state.deviceName, sharingEnabled = state.sharingEnabled, onSharingChange = onSharingChange)
            }
        }

        item(key = "scan") {
            ExpressiveButton(onClick = onOpenScan, modifier = Modifier.fillMaxWidth()) {
                Icon(ShareIcons.QrCode, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Scan QR code")
            }
        }

        item(key = "waiting-title") {
            SectionHeader("Waiting to send", count = state.offers.size)
        }
        if (state.offers.isEmpty()) {
            item(key = "waiting-empty") {
                ReadyToReceive(sharingOff = !state.sharingEnabled, searching = state.status is DiscoveryStatus.Running)
            }
        } else {
            itemsIndexed(state.offers, key = { _, offer -> offer.id }) { index, offer ->
                OfferCard(
                    offer = offer,
                    index = index,
                    modifier = Modifier.animateItem(),
                    onAccept = { vm.accept(offer.id) },
                    onDecline = { vm.decline(offer.id) },
                )
            }
        }

        transferItems("Transfers", incoming, vm, context)
    }
}

/** This device's name as others see it, with the switch that turns sharing on and off. No other text. */
@Composable
private fun NameCard(name: String, sharingEnabled: Boolean, onSharingChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    BouncyCard(
        onClick = null,
        modifier = Modifier.fillMaxWidth(),
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        restRadius = 36.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CookieBadge(
                size = 56.dp,
                color = if (sharingEnabled) scheme.primary else scheme.outline,
                lobes = 8,
                amplitude = 0.07f,
            ) {
                Icon(
                    ShareIcons.Phone,
                    contentDescription = null,
                    tint = if (sharingEnabled) scheme.onPrimary else scheme.surface,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = name.ifBlank { "This device" },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = sharingEnabled, onCheckedChange = onSharingChange)
        }
    }
}

/** A device that wants to send a file: who, what, and Accept or Decline right on the card. */
@Composable
private fun OfferCard(
    offer: IncomingOffer,
    index: Int,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = cardPalette(index)
    BouncyCard(
        onClick = null,
        modifier = modifier.fillMaxWidth().popIn(index),
        color = p.container,
        contentColor = p.onContainer,
        restRadius = 36.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CookieBadge(size = 54.dp, color = p.accent, lobes = 8 + index % 3, amplitude = 0.08f, spin = true) {
                    Icon(ShareIcons.ArrowDown, contentDescription = null, tint = p.onAccent, modifier = Modifier.size(26.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(offer.sender, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "${offer.fileName} · ${offer.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.onContainer.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExpressiveButton(onClick = onDecline, kind = ButtonKind.Tonal, modifier = Modifier.weight(1f)) { Text("Decline") }
                ExpressiveButton(onClick = onAccept, modifier = Modifier.weight(1f)) { Text("Accept") }
            }
        }
    }
}

/** Nobody is sending right now. */
@Composable
private fun ReadyToReceive(sharingOff: Boolean, searching: Boolean) {
    val scheme = MaterialTheme.colorScheme
    BouncyCard(
        onClick = null,
        modifier = Modifier.fillMaxWidth(),
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        restRadius = 40.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                if (searching && !sharingOff) PulseRings(color = scheme.primary)
                CookieBadge(size = 76.dp, color = scheme.tertiaryContainer, lobes = 10, amplitude = 0.07f, spin = !sharingOff) {
                    Icon(
                        ShareIcons.ArrowDown,
                        contentDescription = null,
                        tint = scheme.onTertiaryContainer,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
            Text(
                text = if (sharingOff) "Sharing is off" else "Ready to receive",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = if (sharingOff) {
                    "Turn the switch on to let other devices find you."
                } else {
                    "When someone picks you on their Send screen, they show up here."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
