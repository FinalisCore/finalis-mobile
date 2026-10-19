package com.finalis.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.finalis.mobile.core.model.BalanceSnapshot
import com.finalis.mobile.core.model.ImportedWalletRecord
import com.finalis.mobile.core.model.NetworkIdentity
import com.finalis.mobile.core.model.FinalisNetwork

@Composable
fun StartupSection() {
    WalletPanel(
        title = "Starting wallet",
        subtitle = "Loading the local identity and checking the active lightserver.",
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp)
            Text("Preparing finalized wallet state…")
        }
    }
}

@Composable
fun WalletImportSection(
    importPrivateKeyHex: String,
    importError: String?,
    isImporting: Boolean,
    onImportPrivateKeyChange: (String) -> Unit,
    onImportWallet: () -> Unit,
) {
    WalletPanel(
        title = "Import wallet",
        subtitle = "Use the raw 32-byte private key hex for the existing Finalis identity. The key remains on this device.",
    ) {
        BrandedMarkHeader(
            title = "Single-address V1 wallet",
            subtitle = "One key. One address. Finalized chain state only.",
        )
        EmptyStateBlock(
            title = "No wallet imported",
            detail = "Import the existing normalized private key to restore this wallet address.",
        )
        OutlinedTextField(
            value = importPrivateKeyHex,
            onValueChange = onImportPrivateKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Private key hex") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            minLines = 3,
        )
        if (importError != null) {
            StatusChip(
                text = importError,
                tone = StatusTone.Danger,
            )
        }
        Button(
            enabled = !isImporting,
            onClick = onImportWallet,
        ) {
            Text(if (isImporting) "Importing…" else "Import wallet")
        }
    }
}

@Composable
fun NetworkSelectionSection(
    selectedNetwork: FinalisNetwork,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onSelectNetwork: (FinalisNetwork) -> Unit,
) {
    WalletPanel(
        title = title,
        subtitle = subtitle,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FinalisNetwork.ALL.forEach { network ->
                if (network == selectedNetwork) {
                    Button(enabled = enabled, onClick = {}) { Text(network.displayName) }
                } else {
                    OutlinedButton(enabled = enabled, onClick = { onSelectNetwork(network) }) { Text(network.displayName) }
                }
            }
        }
        LabelValue(
            label = "Address prefix",
            value = "${selectedNetwork.hrp}1…",
            monospace = true,
        )
        if (selectedNetwork.isTestnet) {
            StatusChip(
                text = "Testnet coins have no value.",
                tone = StatusTone.Warning,
            )
        }
    }
}

@Composable
fun WalletIdentitySection(
    wallet: ImportedWalletRecord,
    onCopyAddress: () -> Unit,
    onShareAddress: () -> Unit,
) {
    WalletPanel(
        title = "Wallet identity",
        subtitle = "This device signs locally and always returns change to the same primary address in V1.",
    ) {
        BrandedMarkHeader(
            title = "Primary address",
            subtitle = "The only receive and change address used in V1.",
        )
        CopyableCodeBlock(
            text = wallet.walletProfile.address.value,
            onCopy = onCopyAddress,
            onShare = onShareAddress,
        )
        SectionDivider()
        val network = wallet.walletProfile.network
        LabelValue(
            label = "Network",
            value = "${network.name} (${network.hrp})",
        )
        LabelValue(
            label = "Expected network id",
            value = network.networkId,
            monospace = true,
        )
        LabelValue(
            label = "Expected genesis",
            value = network.genesisHash ?: "(not published yet)",
            monospace = true,
        )
        LabelValue(
            label = "Public key",
            value = wallet.walletProfile.publicKeyHex,
            monospace = true,
        )
    }
}

@Composable
fun WalletSafetySection(
    exportedPrivateKeyHex: String?,
    exportArmed: Boolean,
    resetArmed: Boolean,
    onToggleExport: () -> Unit,
    onHideKey: () -> Unit,
    onToggleReset: () -> Unit,
    onCopyPrivateKey: (String) -> Unit,
) {
    WalletPanel(
        title = "Wallet safety",
        subtitle = "Export reveals the normalized private key hex. Reset clears the stored identity and local submitted transaction records on this device.",
        tonal = true,
    ) {
        if (exportedPrivateKeyHex != null) {
            StatusChip(
                text = "Private key revealed locally",
                tone = StatusTone.Warning,
            )
            CopyableCodeBlock(
                text = exportedPrivateKeyHex,
                onCopy = { onCopyPrivateKey(exportedPrivateKeyHex) },
            )
        }
        if (exportArmed) {
            ValueRow(
                title = "Export warning",
                detail = "Anyone with this key can fully control the wallet. Confirm only in a safe environment.",
                tone = StatusTone.Warning,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = onToggleExport) {
                Text(if (exportArmed) "Confirm export" else "Export wallet")
            }
            OutlinedButton(
                onClick = onHideKey,
                enabled = exportedPrivateKeyHex != null,
            ) {
                Text("Hide key")
            }
        }
        if (resetArmed) {
            ValueRow(
                title = "Reset warning",
                detail = "This removes the stored wallet and local submitted transaction state from this device. Finalized chain state remains on the network.",
                tone = StatusTone.Danger,
            )
        }
        OutlinedButton(onClick = onToggleReset) {
            Text(if (resetArmed) "Confirm reset" else "Reset wallet")
        }
    }
}

@Composable
fun WalletHomeSection(
    status: NetworkIdentity,
    balance: BalanceSnapshot,
    reservedUnits: Long,
    spendableUnits: Long,
    submittedCount: Int,
) {
    WalletPanel(
        title = "Wallet overview",
        subtitle = "Spendable balance and current sendable amount.",
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "SPENDABLE BALANCE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatFinalisUnits(balance.confirmedUnits),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "FLS",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MetricTile(
                        modifier = Modifier.weight(1f),
                        label = "Sendable now",
                        value = formatFinalisAmountLabel(spendableUnits),
                    )
                    MetricTile(
                        modifier = Modifier.weight(1f),
                        label = if (reservedUnits > 0) "Reserved" else "Submitted",
                        value = if (reservedUnits > 0) formatFinalisAmountLabel(reservedUnits) else submittedCount.toString(),
                    )
                }
            }
        }
        if (reservedUnits > 0) {
            ValueRow(
                title = "Reserved after submission",
                detail = "Part of the balance stays reserved until finalized change becomes visible.",
                tone = StatusTone.Warning,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(text = "Finalized tip ${status.finalizedHeight ?: status.tipHeight}", tone = StatusTone.Neutral)
            StatusChip(text = status.serverTruth.replaceFirstChar { it.uppercase() }, tone = StatusTone.Positive)
            if (submittedCount > 0) {
                StatusChip(text = "$submittedCount submitted", tone = StatusTone.Warning)
            }
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
fun RefreshActionsSection(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onResumePolling: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FilledTonalButton(
            onClick = onRefresh,
            enabled = !isRefreshing,
        ) {
            Text(if (isRefreshing) "Refreshing…" else "Refresh")
        }
        OutlinedButton(
            onClick = onResumePolling,
            enabled = !isRefreshing,
        ) {
            Text("Resume submitted polling")
        }
    }
}
