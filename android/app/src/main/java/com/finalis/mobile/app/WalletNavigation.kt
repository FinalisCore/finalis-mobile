package com.finalis.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.model.ImportedWalletRecord
import com.finalis.mobile.core.wallet.WalletSendService

internal enum class WalletTab(
    val label: String,
    val icon: ImageVector,
) {
    Home(label = "Home", icon = Icons.Filled.Home),
    Send(label = "Send", icon = Icons.Filled.ArrowUpward),
    Receive(label = "Receive", icon = Icons.Filled.ArrowDownward),
    Activity(label = "Activity", icon = Icons.AutoMirrored.Filled.List),
    Info(label = "Info", icon = Icons.Filled.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletTopBar(network: FinalisNetwork) {
    TopAppBar(
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Finalis Wallet",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (network.isTestnet) {
                    StatusChip(text = network.name.uppercase(), tone = StatusTone.Warning)
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

@Composable
internal fun WalletBottomBar(
    selectedTab: WalletTab,
    onSelectTab: (WalletTab) -> Unit,
) {
    val iconSize: Dp = 20.dp
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        WalletTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selectedTab,
                onClick = { onSelectTab(tab) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier.padding(top = 2.dp).then(Modifier),
                    )
                },
                label = {
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (tab == selectedTab) FontWeight.SemiBold else FontWeight.Medium,
                    )
                },
            )
        }
    }
}

@Composable
internal fun WalletTabContent(
    modifier: Modifier = Modifier,
    selectedTab: WalletTab,
    wallet: ImportedWalletRecord,
    readState: DashboardState,
    diagnosticsState: DiagnosticsState,
    rpcSettingsState: RpcSettingsState,
    exportedPrivateKeyHex: String?,
    exportArmed: Boolean,
    resetArmed: Boolean,
    isRefreshing: Boolean,
    recipientAddress: String,
    amountUnitsInput: String,
    errorMessage: String?,
    previewState: SendPreviewState?,
    broadcastResult: com.finalis.mobile.core.model.BroadcastResult?,
    isSending: Boolean,
    onSelectTab: (WalletTab) -> Unit,
    onRefresh: () -> Unit,
    onLoadMoreHistory: () -> Unit,
    onResumePolling: () -> Unit,
    onToggleExport: () -> Unit,
    onHideKey: () -> Unit,
    onToggleReset: () -> Unit,
    onCopyPrivateKey: (String) -> Unit,
    onCopyAddress: () -> Unit,
    onShareAddress: () -> Unit,
    onRecipientAddressChange: (String) -> Unit,
    onAmountUnitsChange: (String) -> Unit,
    onNormalizeAmountUnitsInput: () -> Unit,
    onPrepareTransaction: () -> Unit,
    onBroadcastTransaction: () -> Unit,
    onCopyPrimaryAddress: () -> Unit,
    onCopyTxid: (String) -> Unit,
    onRpcEndpointInputChange: (String) -> Unit,
    onAddEndpoint: () -> Unit,
    onSelectEndpoint: (RpcEndpoint) -> Unit,
    onRemoveEndpoint: (RpcEndpoint) -> Unit,
    onSwitchNetwork: (FinalisNetwork) -> Unit,
) {
    when (selectedTab) {
        WalletTab.Home -> HomeTabScreen(
            modifier = modifier,
            readState = readState,
            diagnosticsState = diagnosticsState,
            onSend = { onSelectTab(WalletTab.Send) },
            onReceive = { onSelectTab(WalletTab.Receive) },
            onRefresh = onRefresh,
            isRefreshing = isRefreshing,
        )

        WalletTab.Send -> SendTabScreen(
            modifier = modifier,
            readState = readState,
            wallet = wallet,
            recipientAddress = recipientAddress,
            amountUnitsInput = amountUnitsInput,
            errorMessage = errorMessage,
            previewState = previewState,
            broadcastResult = broadcastResult,
            isSending = isSending,
            onRecipientAddressChange = onRecipientAddressChange,
            onAmountUnitsChange = onAmountUnitsChange,
            onNormalizeAmountUnitsInput = onNormalizeAmountUnitsInput,
            onPrepareTransaction = onPrepareTransaction,
            onBroadcastTransaction = onBroadcastTransaction,
            onCopyPrimaryAddress = onCopyPrimaryAddress,
        )

        WalletTab.Receive -> ReceiveTabScreen(
            modifier = modifier,
            wallet = wallet,
            onCopyAddress = onCopyAddress,
            onShareAddress = onShareAddress,
        )

        WalletTab.Activity -> ActivityTabScreen(
            modifier = modifier,
            readState = readState,
            onCopyTxid = onCopyTxid,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            onLoadMoreHistory = onLoadMoreHistory,
            onResumePolling = onResumePolling,
        )

        WalletTab.Info -> InfoTabScreen(
            modifier = modifier,
            wallet = wallet,
            diagnosticsState = diagnosticsState,
            rpcSettingsState = rpcSettingsState,
            exportedPrivateKeyHex = exportedPrivateKeyHex,
            exportArmed = exportArmed,
            resetArmed = resetArmed,
            onToggleExport = onToggleExport,
            onHideKey = onHideKey,
            onToggleReset = onToggleReset,
            onCopyPrivateKey = onCopyPrivateKey,
            onCopyAddress = onCopyAddress,
            onShareAddress = onShareAddress,
            onRpcEndpointInputChange = onRpcEndpointInputChange,
            onAddEndpoint = onAddEndpoint,
            onSelectEndpoint = onSelectEndpoint,
            onRemoveEndpoint = onRemoveEndpoint,
            onSwitchNetwork = onSwitchNetwork,
        )
    }
}

@Composable
private fun HomeTabScreen(
    modifier: Modifier,
    readState: DashboardState,
    diagnosticsState: DiagnosticsState,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (readState) {
            DashboardState.Empty,
            DashboardState.Loading -> item { FinalizedStateLoadingSection() }
            is DashboardState.Error -> item { ReadErrorSection(message = readState.message) }
            is DashboardState.NetworkMismatch -> {
                item {
                    EndpointHealthSummarySection(
                        diagnosticsState = diagnosticsState,
                    )
                }
                item {
                    NetworkMismatchSection(
                        status = readState.status,
                        mismatch = readState.mismatch,
                        expectedNetwork = readState.expectedNetwork,
                    )
                }
                item {
                    HomeLifecycleSummarySection(
                        lifecycleState = readState.lifecycleState,
                    )
                }
            }
            is DashboardState.Ready -> {
                item {
                    WalletHomeSection(
                        status = readState.status,
                        balance = readState.balance,
                        reservedUnits = readState.reservedUnits,
                        spendableUnits = readState.spendableUnits,
                        submittedCount = readState.submitted.size,
                    )
                }
                item {
                    EndpointHealthSummarySection(
                        diagnosticsState = diagnosticsState,
                    )
                }
                item {
                    HomeQuickActionsSection(
                        isRefreshing = isRefreshing,
                        onSend = onSend,
                        onReceive = onReceive,
                        onRefresh = onRefresh,
                    )
                }
                if (readState.lifecycleState.pendingTransactions.isNotEmpty() ||
                    readState.lifecycleState.recentlyFinalizedTransactions.isNotEmpty()
                ) {
                    item {
                        HomeLifecycleSummarySection(
                            lifecycleState = readState.lifecycleState,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SendTabScreen(
    modifier: Modifier,
    readState: DashboardState,
    wallet: ImportedWalletRecord,
    recipientAddress: String,
    amountUnitsInput: String,
    errorMessage: String?,
    previewState: SendPreviewState?,
    broadcastResult: com.finalis.mobile.core.model.BroadcastResult?,
    isSending: Boolean,
    onRecipientAddressChange: (String) -> Unit,
    onAmountUnitsChange: (String) -> Unit,
    onNormalizeAmountUnitsInput: () -> Unit,
    onPrepareTransaction: () -> Unit,
    onBroadcastTransaction: () -> Unit,
    onCopyPrimaryAddress: () -> Unit,
) {
    val readyState = readState as? DashboardState.Ready
    val mismatch = (readState as? DashboardState.NetworkMismatch)?.mismatch
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SendSection(
                finalizedBalanceUnits = readyState?.balance?.confirmedUnits,
                spendableUnits = readyState?.spendableUnits,
                reservedUnits = readyState?.reservedUnits,
                recipientAddress = recipientAddress,
                amountUnitsInput = amountUnitsInput,
                errorMessage = errorMessage,
                previewState = previewState,
                broadcastResult = broadcastResult,
                isSending = isSending,
                sendBlockedMessage = mismatch?.message,
                primaryAddress = wallet.walletProfile.address.value,
                onRecipientAddressChange = onRecipientAddressChange,
                onAmountUnitsChange = onAmountUnitsChange,
                onNormalizeAmountUnitsInput = onNormalizeAmountUnitsInput,
                onPrepareTransaction = onPrepareTransaction,
                onBroadcastTransaction = onBroadcastTransaction,
                onCopyPrimaryAddress = onCopyPrimaryAddress,
            )
        }
        if (readState is DashboardState.Error) {
            item {
                ReadErrorSection(message = readState.message)
            }
        }
    }
}

@Composable
private fun ReceiveTabScreen(
    modifier: Modifier,
    wallet: ImportedWalletRecord,
    onCopyAddress: () -> Unit,
    onShareAddress: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ReceiveSection(
                wallet = wallet,
                onCopyAddress = onCopyAddress,
                onShareAddress = onShareAddress,
            )
        }
    }
}

@Composable
private fun ActivityTabScreen(
    modifier: Modifier,
    readState: DashboardState,
    onCopyTxid: (String) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onLoadMoreHistory: () -> Unit,
    onResumePolling: () -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (readState) {
            DashboardState.Empty,
            DashboardState.Loading -> item { FinalizedStateLoadingSection() }
            is DashboardState.Error -> item { ReadErrorSection(message = readState.message) }
            is DashboardState.NetworkMismatch -> {
                item {
                    SubmittedTransactionsSection(
                        lifecycleState = readState.lifecycleState,
                        onCopyTxid = onCopyTxid,
                    )
                }
                item {
                    NetworkMismatchSection(
                        status = readState.status,
                        mismatch = readState.mismatch,
                        expectedNetwork = readState.expectedNetwork,
                    )
                }
            }
            is DashboardState.Ready -> {
                item {
                    SubmittedTransactionsSection(
                        lifecycleState = readState.lifecycleState,
                        onCopyTxid = onCopyTxid,
                    )
                }
                item {
                    HistorySection(
                        historyState = readState.historyState,
                        onCopyTxid = onCopyTxid,
                        onLoadMore = onLoadMoreHistory,
                    )
                }
            }
        }
        item {
            RefreshActionsSection(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                onResumePolling = onResumePolling,
            )
        }
    }
}

@Composable
private fun InfoTabScreen(
    modifier: Modifier,
    wallet: ImportedWalletRecord,
    diagnosticsState: DiagnosticsState,
    rpcSettingsState: RpcSettingsState,
    exportedPrivateKeyHex: String?,
    exportArmed: Boolean,
    resetArmed: Boolean,
    onToggleExport: () -> Unit,
    onHideKey: () -> Unit,
    onToggleReset: () -> Unit,
    onCopyPrivateKey: (String) -> Unit,
    onCopyAddress: () -> Unit,
    onShareAddress: () -> Unit,
    onRpcEndpointInputChange: (String) -> Unit,
    onAddEndpoint: () -> Unit,
    onSelectEndpoint: (RpcEndpoint) -> Unit,
    onRemoveEndpoint: (RpcEndpoint) -> Unit,
    onSwitchNetwork: (FinalisNetwork) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            NetworkSelectionSection(
                selectedNetwork = wallet.walletProfile.network,
                title = "Network",
                subtitle = "Switching keeps this key but uses that network's address. Submitted-transaction records are cleared; endpoints are kept per network.",
                enabled = true,
                onSelectNetwork = onSwitchNetwork,
            )
        }
        item {
            DiagnosticsSection(
                rpcSettingsState = rpcSettingsState,
                diagnosticsState = diagnosticsState,
                onRpcEndpointInputChange = onRpcEndpointInputChange,
                onAddEndpoint = onAddEndpoint,
                onSelectEndpoint = onSelectEndpoint,
                onRemoveEndpoint = onRemoveEndpoint,
            )
        }
        item {
            WalletIdentitySection(
                wallet = wallet,
                onCopyAddress = onCopyAddress,
                onShareAddress = onShareAddress,
            )
        }
        item {
            WalletSafetySection(
                exportedPrivateKeyHex = exportedPrivateKeyHex,
                exportArmed = exportArmed,
                resetArmed = resetArmed,
                onToggleExport = onToggleExport,
                onHideKey = onHideKey,
                onToggleReset = onToggleReset,
                onCopyPrivateKey = onCopyPrivateKey,
            )
        }
    }
}

@Composable
private fun EndpointHealthSummarySection(
    diagnosticsState: DiagnosticsState,
) {
    val endpointHealth = diagnosticsState.endpointHealth
    WalletPanel(
        title = "Endpoint",
        subtitle = "Current active lightserver.",
        tonal = true,
    ) {
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
                tone = endpointHealth.syncTrust.toNavigationStatusTone(),
            )
        }
        endpointHealth.status?.let { status ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(text = "Height ${(status.finalizedHeight ?: status.tipHeight)}")
                StatusChip(text = status.name)
            }
        }
        endpointHealth.activeEndpoint?.let {
            LabelValue(
                label = "Active endpoint",
                value = it.url,
                monospace = true,
            )
        }
    }
}

@Composable
private fun HomeQuickActionsSection(
    isRefreshing: Boolean,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onRefresh: () -> Unit,
) {
    WalletPanel(
        title = "Actions",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = onSend,
            ) {
                Text("Send")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onReceive,
            ) {
                Text("Receive")
            }
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onRefresh,
            enabled = !isRefreshing,
        ) {
            Text(if (isRefreshing) "Refreshing…" else "Refresh")
        }
    }
}

@Composable
private fun HomeLifecycleSummarySection(
    lifecycleState: TransactionLifecycleState,
) {
    WalletPanel(
        title = "Recent activity",
        subtitle = "Local submitted state.",
        tonal = true,
    ) {
        if (lifecycleState.pendingTransactions.isEmpty() && lifecycleState.recentlyFinalizedTransactions.isEmpty()) {
            EmptyStateBlock(
                title = "No submitted activity",
                detail = "Submitted transactions will appear here until finalized history catches up.",
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(
                    text = "${lifecycleState.pendingTransactions.size} submitted",
                    tone = if (lifecycleState.pendingTransactions.isEmpty()) StatusTone.Neutral else StatusTone.Warning,
                )
                StatusChip(
                    text = "${lifecycleState.recentlyFinalizedTransactions.size} finalized",
                    tone = if (lifecycleState.recentlyFinalizedTransactions.isEmpty()) StatusTone.Neutral else StatusTone.Positive,
                )
            }
            if (lifecycleState.totalReservedUnits > 0) {
                LabelValue(
                    label = "Reserved",
                    value = formatFinalisAmountLabel(lifecycleState.totalReservedUnits),
                )
            }
            if (lifecycleState.totalPendingDeductionUnits > 0) {
                LabelValue(
                    label = "Submitted spend",
                    value = formatFinalisAmountLabel(lifecycleState.totalPendingDeductionUnits),
                )
            }
        }
    }
}

private fun SyncTrustState.toNavigationStatusTone(): StatusTone =
    when (this) {
        SyncTrustState.HEALTHY -> StatusTone.Positive
        SyncTrustState.DEGRADED -> StatusTone.Warning
        SyncTrustState.MISMATCHED -> StatusTone.Danger
        SyncTrustState.UNREACHABLE -> StatusTone.Danger
        SyncTrustState.UNKNOWN -> StatusTone.Warning
    }
