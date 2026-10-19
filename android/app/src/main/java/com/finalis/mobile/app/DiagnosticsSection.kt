package com.finalis.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.finalis.mobile.core.wallet.WalletSendService
import com.finalis.mobile.data.lightserver.rpcUrlFromExplorerUrl

@Composable
fun EndpointHealthBanner(
    diagnosticsState: DiagnosticsState,
) {
    val endpointHealth = diagnosticsState.endpointHealth
    if (endpointHealth.syncTrust == SyncTrustState.HEALTHY || endpointHealth.syncTrust == SyncTrustState.UNKNOWN) {
        return
    }
    WalletPanel(
        title = "Endpoint warning",
        subtitle = "Current lightserver trust state requires attention.",
        tonal = true,
    ) {
        EmptyStateBlock(
            title = endpointHealth.summary,
            detail = endpointHealth.detail,
            tone = endpointHealth.syncTrust.toStatusTone(),
        )
    }
}

@Composable
fun DiagnosticsSection(
    rpcSettingsState: RpcSettingsState,
    diagnosticsState: DiagnosticsState,
    onRpcEndpointInputChange: (String) -> Unit,
    onAddEndpoint: () -> Unit,
    onSelectEndpoint: (RpcEndpoint) -> Unit,
    onRemoveEndpoint: (RpcEndpoint) -> Unit,
) {
    val endpointHealth = diagnosticsState.endpointHealth
    WalletPanel(
        title = "Connection",
        subtitle = "Active lightserver and saved endpoints.",
    ) {
        LabelValue(
            label = "Active RPC endpoint",
            value = rpcSettingsState.activeEndpoint?.url ?: "None configured",
            monospace = true,
        )
        if (!BuildConfig.USE_MOCK_LIGHTSERVER) {
            EndpointHealthSummary(endpointHealth = endpointHealth)
            SectionDivider()
        }
        if (BuildConfig.USE_MOCK_LIGHTSERVER) {
            EmptyStateBlock(
                title = "Mock Mode",
                detail = "Runtime endpoint selection is disabled while this build uses the mock lightserver repository.",
                tone = StatusTone.Warning,
            )
        } else {
            EndpointSettingsBlock(
                rpcSettingsState = rpcSettingsState,
                endpointHealth = endpointHealth,
                endpointProbes = diagnosticsState.endpointProbes,
                onRpcEndpointInputChange = onRpcEndpointInputChange,
                onAddEndpoint = onAddEndpoint,
                onSelectEndpoint = onSelectEndpoint,
                onRemoveEndpoint = onRemoveEndpoint,
            )
            endpointHealth.status?.let { status ->
                AdvancedConnectionBlock(
                    networkId = status.networkId ?: "(not provided)",
                    genesisHash = status.genesisHash ?: "(not provided)",
                    finalizedTransitionHash = status.finalizedHash ?: status.tipHash,
                    walletApi = status.walletApiVersion,
                    syncHealth = if (status.healthyPeerCount != null || status.establishedPeerCount != null || status.finalizedLag != null) {
                        buildString {
                            append("healthy peers ${status.healthyPeerCount ?: "?"}")
                            append(", established ${status.establishedPeerCount ?: "?"}")
                            append(", finalized lag ${status.finalizedLag ?: "unknown"}")
                        }
                    } else {
                        null
                    },
                    checkpointMode = status.checkpointDerivationMode,
                    fallbackReason = status.checkpointFallbackReason,
                    fallbackSticky = status.fallbackSticky,
                    adaptiveSummary = buildAdaptiveSummary(status),
                    utxoDiagnostics = diagnosticsState.utxoDiagnostics?.let { snapshot ->
                        "total ${snapshot.totalReturned}, finalized-kept ${snapshot.finalizedKept}, filtered-pending ${snapshot.filteredPending}"
                    },
                )
            }
            if (diagnosticsState.txDebugRecords.isNotEmpty()) {
                TransactionDebugBlock(
                    txDebugRecords = diagnosticsState.txDebugRecords,
                )
            }
        }
    }
}

@Composable
private fun EndpointHealthSummary(
    endpointHealth: EndpointHealthState,
) {
    EmptyStateBlock(
        title = endpointHealth.summary,
        detail = endpointHealth.detail,
        tone = endpointHealth.syncTrust.toStatusTone(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(
            text = when (endpointHealth.reachability) {
                EndpointReachabilityState.REACHABLE -> "Reachable"
                EndpointReachabilityState.UNREACHABLE -> "Unreachable"
                EndpointReachabilityState.UNKNOWN -> "Reachability unknown"
            },
            tone = when (endpointHealth.reachability) {
                EndpointReachabilityState.REACHABLE -> StatusTone.Positive
                EndpointReachabilityState.UNREACHABLE -> StatusTone.Danger
                EndpointReachabilityState.UNKNOWN -> StatusTone.Warning
            },
        )
        StatusChip(
            text = when (endpointHealth.syncTrust) {
                SyncTrustState.HEALTHY -> "Healthy"
                SyncTrustState.DEGRADED -> "Degraded"
                SyncTrustState.MISMATCHED -> "Mismatch"
                SyncTrustState.UNREACHABLE -> "Unreachable"
                SyncTrustState.UNKNOWN -> "Unknown"
            },
            tone = endpointHealth.syncTrust.toStatusTone(),
        )
    }
    endpointHealth.activeEndpoint?.let {
        LabelValue(
            label = "Endpoint",
            value = it.url,
            monospace = true,
        )
    }
    endpointHealth.resolvedExplorerUrl?.let {
        LabelValue(
            label = "Resolved explorer URL",
            value = it,
            monospace = true,
        )
    }
    endpointHealth.resolvedRpcUrl?.let {
        LabelValue(
            label = "Resolved RPC URL",
            value = it,
            monospace = true,
        )
    }
    endpointHealth.errorMessage?.let {
        LabelValue(
            label = "Raw transport error",
            value = it,
            monospace = true,
        )
    }
    endpointHealth.status?.let { status ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(text = "Height ${(status.finalizedHeight ?: status.tipHeight)}")
            StatusChip(text = status.name)
        }
    }
}

@Composable
private fun AdvancedConnectionBlock(
    networkId: String,
    genesisHash: String,
    finalizedTransitionHash: String,
    walletApi: String?,
    syncHealth: String?,
    checkpointMode: String?,
    fallbackReason: String?,
    fallbackSticky: Boolean?,
    adaptiveSummary: String?,
    utxoDiagnostics: String?,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide advanced" else "Show advanced")
        }
        if (expanded) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LabelValue(label = "Network id", value = networkId, monospace = true)
                    LabelValue(label = "Genesis", value = genesisHash, monospace = true)
                    LabelValue(label = "Finalized transition hash", value = finalizedTransitionHash, monospace = true)
                    walletApi?.let { LabelValue(label = "Wallet API", value = it) }
                    syncHealth?.let { LabelValue(label = "Sync health", value = it) }
                    checkpointMode?.let { LabelValue(label = "Checkpoint mode", value = it) }
                    fallbackReason?.let { LabelValue(label = "Fallback reason", value = it) }
                    fallbackSticky?.let { LabelValue(label = "Sticky fallback", value = it.toString()) }
                    adaptiveSummary?.let { LabelValue(label = "Adaptive regime", value = it) }
                    utxoDiagnostics?.let { LabelValue(label = "UTXO ingest", value = it) }
                }
            }
        }
    }
}

@Composable
private fun TransactionDebugBlock(
    txDebugRecords: List<TxDebugRecord>,
) {
    var expanded by remember { mutableStateOf(false) }
    var inspectedTxid by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide tx debug" else "Show tx debug")
        }
        if (expanded) {
            val normalizedInspectedTxid = inspectedTxid.trim().lowercase()
            val inspectedRecord = normalizedInspectedTxid
                .takeIf { it.isNotBlank() }
                ?.let { inspectTxDebugRecord(it, txDebugRecords) }
            val recordsToRender = inspectedRecord?.let(::listOf) ?: txDebugRecords
            WalletPanel(
                title = "Transaction Debug",
                subtitle = "Reconciliation evidence for recent local and finalized transaction state.",
                tonal = true,
            ) {
                OutlinedTextField(
                    value = inspectedTxid,
                    onValueChange = { inspectedTxid = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Inspect txid") },
                    placeholder = { Text("46caef73d6282ed2418d4a9c6e84e112ac1c6458ebc7f125a5a4d08ec68096bd") },
                    singleLine = true,
                )
                if (inspectedRecord != null) {
                    EmptyHint("Showing the current wallet-state evidence for one txid. Blank the field to see all recent debug records.")
                }
                recordsToRender.forEach { record ->
                    TxDebugRecordCard(record = record, highlightFullTxid = inspectedRecord != null)
                }
            }
        }
    }
}

@Composable
private fun TxDebugRecordCard(
    record: TxDebugRecord,
    highlightFullTxid: Boolean,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (highlightFullTxid) record.txid else abbreviateDebugTxid(record.txid),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
            )
            LabelValue(label = "Visible category", value = record.visibleActivityCategory.name.lowercase())
            LabelValue(label = "Reconciliation source", value = record.reconciliationSource.name.lowercase())
            LabelValue(label = "Local submitted", value = record.localSubmittedPresent.toString())
            LabelValue(label = "Local recently finalized", value = record.localRecentlyFinalizedPresent.toString())
            LabelValue(label = "Finalized history present", value = record.finalizedHistoryPresent.toString())
            LabelValue(label = "Finalized status confirmed", value = record.finalizedStatusConfirmed.toString())
            LabelValue(label = "Reserved contributor", value = record.reservedBalanceContributor.toString())
            LabelValue(label = "Suppressed duplicate", value = record.suppressedAsDuplicate.toString())
            LabelValue(label = "Why still submitted", value = record.whyStillSubmitted, monospace = true)
            record.suppressionReason?.let {
                LabelValue(label = "Suppression reason", value = it, monospace = true)
            }
            record.lastObservedHeight?.let {
                LabelValue(label = "Last observed height", value = it.toString())
            }
            ValueRow(
                title = "Summary",
                detail = record.debugSummary,
                tone = StatusTone.Neutral,
            )
        }
    }
}

private fun abbreviateDebugTxid(txid: String): String =
    if (txid.length <= 18) txid else "${txid.take(12)}…${txid.takeLast(6)}"

@Composable
private fun EndpointSettingsBlock(
    rpcSettingsState: RpcSettingsState,
    endpointHealth: EndpointHealthState,
    endpointProbes: List<EndpointProbeResult>,
    onRpcEndpointInputChange: (String) -> Unit,
    onAddEndpoint: () -> Unit,
    onSelectEndpoint: (RpcEndpoint) -> Unit,
    onRemoveEndpoint: (RpcEndpoint) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (rpcSettingsState.message != null) {
            EmptyStateBlock(
                title = "Endpoint status",
                detail = rpcSettingsState.message,
                tone = if (rpcSettingsState.message.contains("Active endpoint set")) {
                    StatusTone.Positive
                } else {
                    StatusTone.Warning
                },
            )
        }

        OutlinedTextField(
            value = rpcSettingsState.inputValue,
            onValueChange = onRpcEndpointInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Add endpoint URL") },
            placeholder = { Text("http://lightserver.example.com:18080") },
            singleLine = true,
        )

        OutlinedButton(onClick = onAddEndpoint) {
            Text("Add Endpoint")
        }

        if (rpcSettingsState.savedEndpoints.isEmpty()) {
            EmptyHint("No runtime endpoints are saved yet. The build default endpoint will be used until one is added.")
        } else {
            rpcSettingsState.savedEndpoints.forEach { endpoint ->
                val endpointProbe = endpointProbes.firstOrNull { it.endpoint.url == endpoint.url }
                RpcEndpointRow(
                    endpoint = endpoint,
                    isActive = endpoint.url == rpcSettingsState.activeEndpoint?.url,
                    endpointHealth = endpointHealth.takeIf { endpoint.url == rpcSettingsState.activeEndpoint?.url },
                    endpointProbe = endpointProbe,
                    onSelectEndpoint = { onSelectEndpoint(endpoint) },
                    onRemoveEndpoint = { onRemoveEndpoint(endpoint) },
                )
            }
        }
    }
}

@Composable
private fun RpcEndpointRow(
    endpoint: RpcEndpoint,
    isActive: Boolean,
    endpointHealth: EndpointHealthState?,
    endpointProbe: EndpointProbeResult?,
    onSelectEndpoint: () -> Unit,
    onRemoveEndpoint: () -> Unit,
) {
    WalletPanel(
        title = if (isActive) "Saved Endpoint • Active" else "Saved Endpoint",
        tonal = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = endpoint.url,
                fontFamily = FontFamily.Monospace,
            )
            LabelValue(
                label = "Explorer URL",
                value = endpoint.url,
                monospace = true,
            )
            LabelValue(
                label = "RPC URL",
                value = rpcUrlFromExplorerUrl(endpoint.url),
                monospace = true,
            )
            if (endpointHealth != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(
                        text = when (endpointHealth.reachability) {
                            EndpointReachabilityState.REACHABLE -> "Reachable"
                            EndpointReachabilityState.UNREACHABLE -> "Unreachable"
                            EndpointReachabilityState.UNKNOWN -> "Unknown"
                        },
                        tone = when (endpointHealth.reachability) {
                            EndpointReachabilityState.REACHABLE -> StatusTone.Positive
                            EndpointReachabilityState.UNREACHABLE -> StatusTone.Danger
                            EndpointReachabilityState.UNKNOWN -> StatusTone.Warning
                        },
                    )
                    StatusChip(
                        text = when (endpointHealth.syncTrust) {
                            SyncTrustState.HEALTHY -> "Healthy"
                            SyncTrustState.DEGRADED -> "Degraded"
                            SyncTrustState.MISMATCHED -> "Mismatch"
                            SyncTrustState.UNREACHABLE -> "Unreachable"
                            SyncTrustState.UNKNOWN -> "Unknown"
                        },
                        tone = endpointHealth.syncTrust.toStatusTone(),
                    )
                }
            }
            endpointProbe?.error?.let { failure ->
                LabelValue(
                    label = "Last probe error",
                    value = failure.rawMessage ?: failure.message,
                    monospace = true,
                )
            }
            endpointProbe?.status?.let { status ->
                LabelValue(
                    label = "Last probe finalized height",
                    value = (status.finalizedHeight ?: status.tipHeight).toString(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isActive) {
                    StatusChip(text = "Active", tone = StatusTone.Positive)
                } else {
                    OutlinedButton(onClick = onSelectEndpoint) {
                        Text("Use Endpoint")
                    }
                }
                OutlinedButton(onClick = onRemoveEndpoint) {
                    Text("Remove")
                }
            }
        }
    }
}

private fun SyncTrustState.toStatusTone(): StatusTone =
    when (this) {
        SyncTrustState.HEALTHY -> StatusTone.Positive
        SyncTrustState.DEGRADED -> StatusTone.Warning
        SyncTrustState.MISMATCHED -> StatusTone.Danger
        SyncTrustState.UNREACHABLE -> StatusTone.Danger
        SyncTrustState.UNKNOWN -> StatusTone.Warning
    }

private fun buildAdaptiveSummary(status: com.finalis.mobile.core.model.NetworkIdentity): String? {
    val segments = buildList {
        status.qualifiedDepth?.let { add("depth $it") }
        status.adaptiveTargetCommitteeSize?.let { add("target $it") }
        status.adaptiveMinEligible?.let { add("min eligible $it") }
        status.adaptiveMinBond?.let { add("min bond $it") }
        status.adaptiveSlack?.let { add("slack $it") }
        status.targetExpandStreak?.let { add("expand streak $it") }
        status.targetContractStreak?.let { add("contract streak $it") }
        status.adaptiveFallbackRateBps?.let { add("fallback rate ${it}bps") }
        status.adaptiveStickyFallbackRateBps?.let { add("sticky fallback rate ${it}bps") }
        status.adaptiveTelemetryWindowEpochs?.let { add("telemetry window $it") }
    }
    return segments.takeIf { it.isNotEmpty() }?.joinToString(", ")
}
