package com.finalis.mobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.finalis.mobile.core.model.HistoryEntry
import com.finalis.mobile.core.model.NetworkIdentity
import com.finalis.mobile.core.model.TxDetail
import com.finalis.mobile.core.model.TxDirection
import com.finalis.mobile.core.wallet.NetworkMismatch as WalletNetworkMismatch
import com.finalis.mobile.core.model.FinalisNetwork

@Composable
fun FinalizedStateLoadingSection() {
    WalletPanel(
        title = "Finalized state",
        subtitle = "Waiting for finalized balance and history from the connected lightserver.",
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp)
            Text("Loading finalized balance and history…")
        }
    }
}

@Composable
fun ReadErrorSection(
    message: String,
) {
    WalletPanel(
        title = "Read error",
        subtitle = "The wallet identity is intact, but finalized mainnet data could not be loaded.",
        tonal = true,
    ) {
        EmptyStateBlock(
            title = "Read unavailable",
            detail = message,
            tone = StatusTone.Danger,
        )
    }
}

@Composable
fun NetworkMismatchSection(
    status: NetworkIdentity,
    mismatch: WalletNetworkMismatch,
    expectedNetwork: FinalisNetwork,
) {
    WalletPanel(
        title = "Invalid network endpoint",
        subtitle = "This wallet is on Finalis ${expectedNetwork.name}. Reads and sending stay blocked until the endpoint matches ${expectedNetwork.name}.",
        tonal = true,
    ) {
        EmptyStateBlock(
            title = "Invalid network endpoint",
            detail = "Switch to a published Finalis ${expectedNetwork.name} lightserver before refreshing balance, history, or sending funds.",
            tone = StatusTone.Warning,
        )
        ValueRow(
            title = "Reason",
            detail = mismatch.message,
            tone = StatusTone.Warning,
        )
        SectionDivider()
        LabelValue(label = "Endpoint network", value = status.name)
        LabelValue(label = "Endpoint network id", value = status.networkId ?: "(not provided)", monospace = true)
        LabelValue(label = "Endpoint genesis", value = status.genesisHash ?: "(not provided)", monospace = true)
        SectionDivider()
        LabelValue(label = "Required network", value = expectedNetwork.name)
        LabelValue(label = "Required HRP", value = expectedNetwork.hrp, monospace = true)
        LabelValue(label = "Required network id", value = expectedNetwork.networkId, monospace = true)
        LabelValue(label = "Required genesis", value = expectedNetwork.genesisHash ?: "(not published yet)", monospace = true)
    }
}

@Composable
fun SubmittedTransactionsSection(
    lifecycleState: TransactionLifecycleState,
    onCopyTxid: (String) -> Unit,
) {
    WalletPanel(
        title = "Submitted activity",
        subtitle = "Local submissions awaiting finalized visibility.",
        tonal = true,
    ) {
        if (lifecycleState.pendingTransactions.isEmpty() && lifecycleState.recentlyFinalizedTransactions.isEmpty()) {
            EmptyStateBlock(
                title = "No submitted transactions",
                detail = "Transactions submitted from this device will appear here until finalized history catches up.",
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (lifecycleState.pendingTransactions.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(text = "${lifecycleState.pendingTransactions.size} submitted", tone = StatusTone.Warning)
                        StatusChip(text = "${formatFinalisAmountLabel(lifecycleState.totalReservedUnits)} reserved", tone = StatusTone.Warning)
                        StatusChip(text = "${formatFinalisAmountLabel(lifecycleState.totalPendingDeductionUnits)} outgoing", tone = StatusTone.Neutral)
                    }
                }
                lifecycleState.pendingTransactions.forEach { pendingTransaction ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusChip(text = "Submitted", tone = StatusTone.Warning)
                                StatusChip(text = formatFinalisAmountLabel(pendingTransaction.amountUnits), tone = StatusTone.Neutral)
                            }
                            Text(
                                text = pendingTransaction.recipientAddress,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                LabelValue(label = "Spend", value = formatFinalisAmountLabel(pendingTransaction.totalDeductionUnits))
                                LabelValue(label = "Reserved", value = formatFinalisAmountLabel(pendingTransaction.reservedUnits))
                            }
                            OutlinedButton(onClick = { onCopyTxid(pendingTransaction.txid) }) {
                                Text("Copy txid")
                            }
                        }
                    }
                }
                lifecycleState.recentlyFinalizedTransactions.forEach { finalizedTransaction ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusChip(text = "Finalized", tone = StatusTone.Positive)
                                StatusChip(text = formatFinalisAmountLabel(finalizedTransaction.amountUnits), tone = StatusTone.Neutral)
                            }
                            OutlinedButton(onClick = { onCopyTxid(finalizedTransaction.txid) }) {
                                Text("Copy txid")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistorySection(
    historyState: FinalizedHistoryState,
    onCopyTxid: (String) -> Unit,
    onLoadMore: () -> Unit,
) {
    WalletPanel(
        title = "Finalized history",
        subtitle = "Latest finalized history from the connected lightserver.",
    ) {
        if (historyState.entries.isEmpty()) {
            EmptyStateBlock(
                title = "No finalized history",
                detail = "This address has no finalized on-chain history yet, or the connected lightserver does not currently report any finalized records.",
            )
        } else {
            val visibleEntries = finalizedHistoryEntriesNewestFirst(historyState.entries)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                visibleEntries.forEach { entry ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusChip(
                                    text = entry.direction.displayName(),
                                    tone = when (entry.direction) {
                                        TxDirection.RECEIVE -> StatusTone.Positive
                                        TxDirection.SEND -> StatusTone.Warning
                                        TxDirection.SELF -> StatusTone.Neutral
                                        TxDirection.UNKNOWN -> StatusTone.Neutral
                                    },
                                )
                                StatusChip(text = "Height ${entry.height}")
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = formatFinalisAmountLabel(entry.netValue),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                OutlinedButton(onClick = { onCopyTxid(entry.txid) }) {
                                    Text("Copy")
                                }
                            }
                            Text(
                                text = abbreviateTxid(entry.txid),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                when {
                    historyState.isLoadingMore -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                            Text("Loading more finalized history…")
                        }
                    }
                    historyState.hasMore -> {
                        OutlinedButton(onClick = onLoadMore) {
                            Text("Load more")
                        }
                    }
                    else -> {
                        EmptyHint("Reached the end of finalized history reported by this endpoint.")
                    }
                }
                historyState.loadMoreError?.let {
                    EmptyStateBlock(
                        title = "More history unavailable",
                        detail = it,
                        tone = StatusTone.Warning,
                    )
                }
            }
        }
    }
}

private fun abbreviateTxid(txid: String): String =
    if (txid.length <= 20) txid else "${txid.take(12)}...${txid.takeLast(8)}"

@Composable
fun TxDetailSection(
    txDetail: TxDetail?,
    onCopyTxid: (String) -> Unit,
) {
    WalletPanel(
        title = "Transaction detail",
        subtitle = "Most recent finalized transaction detail.",
    ) {
        if (txDetail == null) {
            EmptyStateBlock(
                title = "No transaction detail",
                detail = "Once finalized history is available, the wallet shows the most recent finalized transaction detail here.",
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(text = "${txDetail.inputs.size} inputs")
                StatusChip(text = "${txDetail.outputs.size} outputs")
                StatusChip(text = "Height ${txDetail.height}")
            }
            CopyableCodeBlock(
                text = txDetail.txid,
                onCopy = { onCopyTxid(txDetail.txid) },
            )
            txDetail.finalizedTransitionHash?.let {
                LabelValue(label = "Finalized transition hash", value = it, monospace = true)
            }
            txDetail.finalizedDepth?.let {
                LabelValue(label = "Finalized depth", value = it.toString())
            }
        }
    }
}
