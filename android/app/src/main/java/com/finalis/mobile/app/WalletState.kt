package com.finalis.mobile.app

import com.finalis.mobile.core.model.BalanceSnapshot
import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.model.HistoryEntry
import com.finalis.mobile.core.model.HistoryPageResult
import com.finalis.mobile.core.model.NetworkIdentity
import com.finalis.mobile.core.model.SubmittedTransactionRecord
import com.finalis.mobile.core.model.TxDetail
import com.finalis.mobile.core.model.TxDirection
import com.finalis.mobile.core.model.TxStatus
import com.finalis.mobile.core.model.WalletOutPoint
import com.finalis.mobile.core.model.WalletUtxo
import com.finalis.mobile.data.lightserver.UtxoDiagnosticsSnapshot
import com.finalis.mobile.data.lightserver.rpcUrlFromExplorerUrl
import com.finalis.mobile.core.wallet.NetworkMismatch as WalletNetworkMismatch

sealed interface DashboardState {
    data object Empty : DashboardState
    data object Loading : DashboardState
    data class Error(val message: String) : DashboardState
    data class NetworkMismatch(
        val status: NetworkIdentity,
        val submitted: List<SubmittedTransactionRecord>,
        val mismatch: WalletNetworkMismatch,
        val lifecycleState: TransactionLifecycleState,
        val expectedNetwork: FinalisNetwork = FinalisNetwork.MAINNET,
    ) : DashboardState

    data class Ready(
        val status: NetworkIdentity,
        val balance: BalanceSnapshot,
        val reservedUnits: Long,
        val spendableUnits: Long,
        val historyState: FinalizedHistoryState,
        val txDetail: TxDetail?,
        val submitted: List<SubmittedTransactionRecord>,
        val lifecycleState: TransactionLifecycleState,
    ) : DashboardState
}

data class WalletFundsSummary(
    val reservedUnits: Long,
    val spendableUnits: Long,
)

data class PendingTransactionState(
    val txid: String,
    val recipientAddress: String,
    val amountUnits: Long,
    val appliedFeeUnits: Long,
    val totalDeductionUnits: Long,
    val reservedUnits: Long,
    val consumedInputCount: Int,
    val createdAtEpochMillis: Long,
)

data class TransactionLifecycleState(
    val pendingTransactions: List<PendingTransactionState>,
    val recentlyFinalizedTransactions: List<SubmittedTransactionRecord>,
    val totalReservedUnits: Long,
    val totalPendingDeductionUnits: Long,
)

data class SendPreviewState(
    val recipientAddress: String,
    val amountUnits: Long,
    val feeUnits: Long,
    val totalDeductionUnits: Long,
    val reservedByThisTransactionUnits: Long,
    val currentSpendableUnits: Long?,
    val currentReservedUnits: Long?,
    val availableAfterReservationUnits: Long?,
    val reservedAfterBroadcastUnits: Long?,
    val changeUnits: Long,
    val inputCount: Int,
    val outputCount: Int,
    val txid: String,
)

enum class EndpointReachabilityState {
    REACHABLE,
    UNREACHABLE,
    UNKNOWN,
}

enum class EndpointErrorKind {
    UNAVAILABLE,
    RPC_ERROR,
    MALFORMED_DATA,
    INCOMPATIBLE,
    ADDRESS_INVALID,
    ADDRESS_WRONG_NETWORK,
    UNKNOWN,
}

enum class SyncTrustState {
    HEALTHY,
    DEGRADED,
    MISMATCHED,
    UNREACHABLE,
    UNKNOWN,
}

data class EndpointHealthState(
    val activeEndpoint: RpcEndpoint? = null,
    val resolvedExplorerUrl: String? = null,
    val resolvedRpcUrl: String? = null,
    val reachability: EndpointReachabilityState = EndpointReachabilityState.UNKNOWN,
    val syncTrust: SyncTrustState = SyncTrustState.UNKNOWN,
    val status: NetworkIdentity? = null,
    val mismatch: WalletNetworkMismatch? = null,
    val errorKind: EndpointErrorKind? = null,
    val errorMessage: String? = null,
    val summary: String = "No endpoint selected",
    val detail: String = "Add or select a lightserver endpoint to load wallet state.",
)

data class DiagnosticsState(
    val endpointHealth: EndpointHealthState = EndpointHealthState(),
    val endpointProbes: List<EndpointProbeResult> = emptyList(),
    val txDebugRecords: List<TxDebugRecord> = emptyList(),
    val utxoDiagnostics: UtxoDiagnosticsSnapshot? = null,
)

data class RpcEndpoint(
    val url: String,
)

data class RpcSettingsState(
    val savedEndpoints: List<RpcEndpoint> = emptyList(),
    val activeEndpoint: RpcEndpoint? = null,
    val network: FinalisNetwork = FinalisNetwork.MAINNET,
    val inputValue: String = "",
    val message: String? = null,
)

data class EndpointProbeResult(
    val endpoint: RpcEndpoint,
    val status: NetworkIdentity? = null,
    val mismatch: WalletNetworkMismatch? = null,
    val error: EndpointFailure? = null,
) {
    val isValid: Boolean
        get() = status != null && mismatch == null && error == null
}

data class EndpointFailure(
    val kind: EndpointErrorKind,
    val message: String,
    val rawMessage: String? = null,
)

data class FinalizedHistoryState(
    val entries: List<HistoryEntry> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
)

enum class TxVisibleActivityCategory {
    SUBMITTED,
    RECENTLY_FINALIZED,
    FINALIZED_HISTORY,
    NONE,
}

enum class TxReconciliationSource {
    LOCAL_ONLY,
    TX_STATUS,
    HISTORY_PAGE,
    BOUNDED_HISTORY_WINDOW,
}

data class TxDebugRecord(
    val txid: String,
    val localSubmittedPresent: Boolean,
    val localRecentlyFinalizedPresent: Boolean,
    val finalizedHistoryPresent: Boolean,
    val finalizedStatusConfirmed: Boolean,
    val visibleActivityCategory: TxVisibleActivityCategory,
    val reservedBalanceContributor: Boolean,
    val suppressedAsDuplicate: Boolean,
    val suppressionReason: String? = null,
    val reconciliationSource: TxReconciliationSource,
    val lastObservedHeight: Long? = null,
    val whyStillSubmitted: String,
    val debugSummary: String,
)

fun finalizedHistoryEntriesNewestFirst(
    entries: List<HistoryEntry>,
): List<HistoryEntry> =
    entries.sortedWith(
        compareByDescending<HistoryEntry> { it.height }
            .thenByDescending { it.txid.lowercase() },
    )

fun TxDirection.displayName(): String = when (this) {
    TxDirection.RECEIVE -> "Receive"
    TxDirection.SEND -> "Send"
    TxDirection.SELF -> "Self"
    TxDirection.UNKNOWN -> "Unknown"
}

fun TxStatus.displayName(): String = when (this) {
    TxStatus.FINALIZED -> "Finalized"
    TxStatus.SUBMITTED -> "Submitted"
    TxStatus.REJECTED -> "Rejected"
    TxStatus.UNKNOWN -> "Unknown"
}

fun summarizeWalletFunds(
    finalizedBalance: Long,
    finalizedUtxos: List<WalletUtxo>,
    submitted: List<SubmittedTransactionRecord>,
): WalletFundsSummary {
    val reservedUnits = buildPendingTransactionStates(
        finalizedUtxos = finalizedUtxos,
        submitted = submitted,
    ).sumOf { it.reservedUnits }
    return WalletFundsSummary(
        reservedUnits = reservedUnits,
        spendableUnits = (finalizedBalance - reservedUnits).coerceAtLeast(0L),
    )
}

fun buildTransactionLifecycleState(
    finalizedUtxos: List<WalletUtxo>,
    submitted: List<SubmittedTransactionRecord>,
    recentlyFinalizedTransactions: List<SubmittedTransactionRecord> = emptyList(),
    finalizedHistoryEntries: List<HistoryEntry> = emptyList(),
    finalizedHistoryTxids: Set<String> = finalizedHistoryEntries.map { it.txid.lowercase() }.toSet(),
): TransactionLifecycleState {
    val pendingTransactions = buildPendingTransactionStates(
        finalizedUtxos = finalizedUtxos,
        submitted = submitted,
    )
    val visibleRecentlyFinalizedTransactions = filterRecentlyFinalizedTransactions(
        recentlyFinalizedTransactions = recentlyFinalizedTransactions,
        finalizedHistoryTxids = finalizedHistoryTxids,
    )
    return TransactionLifecycleState(
        pendingTransactions = pendingTransactions,
        recentlyFinalizedTransactions = visibleRecentlyFinalizedTransactions,
        totalReservedUnits = pendingTransactions.sumOf { it.reservedUnits },
        totalPendingDeductionUnits = pendingTransactions.sumOf { it.totalDeductionUnits },
    )
}

fun buildSendPreviewState(
    preview: com.finalis.mobile.core.wallet.BuiltTransaction,
    currentSpendableUnits: Long?,
    currentReservedUnits: Long?,
): SendPreviewState {
    val reservedByThisTransactionUnits = preview.inputs.sumOf { it.valueUnits }
    return SendPreviewState(
        recipientAddress = preview.recipientAddress,
        amountUnits = preview.amountUnits,
        feeUnits = preview.appliedFeeUnits,
        totalDeductionUnits = preview.amountUnits + preview.appliedFeeUnits,
        reservedByThisTransactionUnits = reservedByThisTransactionUnits,
        currentSpendableUnits = currentSpendableUnits,
        currentReservedUnits = currentReservedUnits,
        availableAfterReservationUnits = currentSpendableUnits?.minus(reservedByThisTransactionUnits)?.coerceAtLeast(0L),
        reservedAfterBroadcastUnits = currentReservedUnits?.plus(reservedByThisTransactionUnits),
        changeUnits = preview.changeUnits,
        inputCount = preview.inputs.size,
        outputCount = preview.outputs.size,
        txid = preview.txid,
    )
}

fun buildDiagnosticsState(
    activeEndpoint: RpcEndpoint?,
    status: NetworkIdentity?,
    mismatch: WalletNetworkMismatch? = null,
    error: EndpointFailure? = null,
    endpointProbes: List<EndpointProbeResult> = emptyList(),
    txDebugRecords: List<TxDebugRecord> = emptyList(),
    utxoDiagnostics: UtxoDiagnosticsSnapshot? = null,
): DiagnosticsState {
    val endpointHealth = when {
        activeEndpoint == null -> EndpointHealthState()
        error != null -> EndpointHealthState(
            activeEndpoint = activeEndpoint,
            resolvedExplorerUrl = activeEndpoint.url,
            resolvedRpcUrl = rpcUrlFromExplorerUrl(activeEndpoint.url),
            reachability = if (error.kind == EndpointErrorKind.UNAVAILABLE) EndpointReachabilityState.UNREACHABLE else EndpointReachabilityState.REACHABLE,
            syncTrust = if (error.kind == EndpointErrorKind.UNAVAILABLE) SyncTrustState.UNREACHABLE else SyncTrustState.DEGRADED,
            errorKind = error.kind,
            errorMessage = error.rawMessage ?: error.message,
            summary = when (error.kind) {
                EndpointErrorKind.UNAVAILABLE -> "Endpoint unreachable"
                EndpointErrorKind.RPC_ERROR -> "Endpoint RPC error"
                EndpointErrorKind.MALFORMED_DATA -> "Endpoint returned malformed data"
                EndpointErrorKind.INCOMPATIBLE -> "Endpoint contract incompatible"
                EndpointErrorKind.ADDRESS_INVALID -> "Address rejected"
                EndpointErrorKind.ADDRESS_WRONG_NETWORK -> "Address on wrong network"
                EndpointErrorKind.UNKNOWN -> "Endpoint error"
            },
            detail = error.message,
        )

        mismatch != null -> EndpointHealthState(
            activeEndpoint = activeEndpoint,
            resolvedExplorerUrl = activeEndpoint.url,
            resolvedRpcUrl = rpcUrlFromExplorerUrl(activeEndpoint.url),
            reachability = EndpointReachabilityState.REACHABLE,
            syncTrust = SyncTrustState.MISMATCHED,
            status = status,
            mismatch = mismatch,
            summary = "Endpoint network mismatch",
            detail = mismatch.message,
        )

        status != null -> buildHealthyOrDegradedEndpointHealth(activeEndpoint, status)
        else -> EndpointHealthState(
            activeEndpoint = activeEndpoint,
            resolvedExplorerUrl = activeEndpoint.url,
            resolvedRpcUrl = rpcUrlFromExplorerUrl(activeEndpoint.url),
            summary = "Endpoint status unknown",
            detail = "No endpoint health information is currently available.",
        )
    }
    return DiagnosticsState(
        endpointHealth = endpointHealth,
        endpointProbes = endpointProbes,
        txDebugRecords = txDebugRecords,
        utxoDiagnostics = utxoDiagnostics,
    )
}

fun appendHistoryPage(
    existing: FinalizedHistoryState,
    page: HistoryPageResult,
): FinalizedHistoryState {
    val merged = buildList {
        addAll(existing.entries)
        page.items.forEach { incoming ->
            if (none { current -> current.txid == incoming.txid }) add(incoming)
        }
    }
    return existing.copy(
        entries = merged,
        nextCursor = page.nextCursor,
        hasMore = page.hasMore,
        isLoadingMore = false,
        loadMoreError = null,
    )
}

fun filterRecentlyFinalizedTransactions(
    recentlyFinalizedTransactions: List<SubmittedTransactionRecord>,
    finalizedHistoryTxids: Set<String>,
): List<SubmittedTransactionRecord> {
    val normalizedFinalizedHistoryTxids = finalizedHistoryTxids.map { it.lowercase() }.toSet()
    return recentlyFinalizedTransactions.filterNot { record ->
        normalizedFinalizedHistoryTxids.contains(record.txid.lowercase())
    }
}

fun buildTxDebugRecords(
    submitted: List<SubmittedTransactionRecord>,
    recentlyFinalizedTransactions: List<SubmittedTransactionRecord>,
    pendingTransactions: List<PendingTransactionState>,
    visibleHistoryEntries: List<HistoryEntry>,
    finalizedWindow: FinalizedHistoryWindow,
    reconciliationEvidence: Map<String, SubmittedTxReconciliationEvidence>,
): List<TxDebugRecord> {
    val pendingByTxid = pendingTransactions.associateBy { it.txid.lowercase() }
    val submittedByTxid = submitted.associateBy { it.txid.lowercase() }
    val recentlyFinalizedByTxid = recentlyFinalizedTransactions.associateBy { it.txid.lowercase() }
    val visibleHistoryByTxid = visibleHistoryEntries.associateBy { it.txid.lowercase() }
    val txids = linkedSetOf<String>()
    submittedByTxid.keys.forEach(txids::add)
    recentlyFinalizedByTxid.keys.forEach(txids::add)
    visibleHistoryByTxid.keys.forEach(txids::add)
    reconciliationEvidence.keys.forEach(txids::add)

    return txids.map { normalizedTxid ->
        val pending = pendingByTxid[normalizedTxid]
        val localSubmittedPresent = submittedByTxid.containsKey(normalizedTxid)
        val localRecentlyFinalizedPresent = recentlyFinalizedByTxid.containsKey(normalizedTxid)
        val visibleHistory = visibleHistoryByTxid[normalizedTxid]
        val evidence = reconciliationEvidence[normalizedTxid]
        val finalizedHistoryPresent = finalizedWindow.txHeights.containsKey(normalizedTxid)
        val suppressedAsDuplicate = localRecentlyFinalizedPresent && finalizedHistoryPresent
        val suppressionReason = when {
            !suppressedAsDuplicate -> null
            finalizedWindow.visiblePageTxids.contains(normalizedTxid) -> "finalized_history_contains_same_txid"
            else -> "bounded_finalized_history_window_contains_same_txid"
        }
        val visibleActivityCategory = when {
            visibleHistory != null -> TxVisibleActivityCategory.FINALIZED_HISTORY
            localSubmittedPresent -> TxVisibleActivityCategory.SUBMITTED
            localRecentlyFinalizedPresent && !suppressedAsDuplicate -> TxVisibleActivityCategory.RECENTLY_FINALIZED
            else -> TxVisibleActivityCategory.NONE
        }
        val reconciliationSource = when {
            visibleHistory != null -> TxReconciliationSource.HISTORY_PAGE
            evidence != null -> evidence.source
            else -> TxReconciliationSource.LOCAL_ONLY
        }
        val lastObservedHeight = visibleHistory?.height ?: finalizedWindow.txHeights[normalizedTxid] ?: evidence?.lastObservedHeight
        val reservedBalanceContributor = pending != null && pending.reservedUnits > 0L
        val whyStillSubmitted = when {
            visibleActivityCategory == TxVisibleActivityCategory.SUBMITTED &&
                evidence?.finalizedStatusConfirmed == true ->
                "finalized_status_known_but_local_state_not_reconciled"
            visibleActivityCategory == TxVisibleActivityCategory.SUBMITTED &&
                finalizedHistoryPresent ->
                "finalized_history_known_but_local_state_not_reconciled"
            visibleActivityCategory == TxVisibleActivityCategory.SUBMITTED ->
                "still_in_local_submitted_bucket_and_no_finalized_evidence_loaded"
            visibleActivityCategory == TxVisibleActivityCategory.RECENTLY_FINALIZED ->
                "submitted_state_cleared_local_recently_finalized_bridge_active"
            visibleActivityCategory == TxVisibleActivityCategory.FINALIZED_HISTORY ->
                "finalized_history_replaced_local_submission_state"
            suppressedAsDuplicate ->
                suppressionReason ?: "finalized_history_replaced_local_bridge_state"
            evidence?.finalizedStatusConfirmed == true ->
                "finalized_status_known_and_local_submission_state_cleared"
            else ->
                "tx_not_present_in_current_wallet_state"
        }
        val summary = when (visibleActivityCategory) {
            TxVisibleActivityCategory.SUBMITTED ->
                when (whyStillSubmitted) {
                    "finalized_status_known_but_local_state_not_reconciled" ->
                        "Shown as submitted even though direct tx-status evidence says finalized; local submitted state has not been reconciled out yet."
                    "finalized_history_known_but_local_state_not_reconciled" ->
                        "Shown as submitted even though finalized backend history already contains the same txid; local submitted state has not been reconciled out yet."
                    else ->
                        "Shown as submitted because only local submitted state is active; reserved balance still includes its consumed finalized input."
                }
            TxVisibleActivityCategory.RECENTLY_FINALIZED ->
                "Shown as recently finalized because backend finalization was observed, but no finalized history row is visible yet."
            TxVisibleActivityCategory.FINALIZED_HISTORY ->
                "Shown from finalized backend history; local bridge state is suppressed when present."
            TxVisibleActivityCategory.NONE ->
                when {
                    suppressedAsDuplicate -> "Not shown in a local bridge bucket because finalized backend history already contains the same txid."
                    evidence?.finalizedStatusConfirmed == true -> "Finalized by direct tx-status lookup, but not present on the currently visible finalized history page."
                    else -> "No visible activity bucket currently contains this tx."
                }
        }
        TxDebugRecord(
            txid = normalizedTxid,
            localSubmittedPresent = localSubmittedPresent,
            localRecentlyFinalizedPresent = localRecentlyFinalizedPresent,
            finalizedHistoryPresent = finalizedHistoryPresent,
            finalizedStatusConfirmed = evidence?.finalizedStatusConfirmed == true,
            visibleActivityCategory = visibleActivityCategory,
            reservedBalanceContributor = reservedBalanceContributor,
            suppressedAsDuplicate = suppressedAsDuplicate,
            suppressionReason = suppressionReason,
            reconciliationSource = reconciliationSource,
            lastObservedHeight = lastObservedHeight,
            whyStillSubmitted = whyStillSubmitted,
            debugSummary = summary,
        )
    }
}

fun inspectTxDebugRecord(
    txid: String,
    txDebugRecords: List<TxDebugRecord>,
): TxDebugRecord {
    val normalizedTxid = txid.lowercase()
    return txDebugRecords.firstOrNull { it.txid.lowercase() == normalizedTxid }
        ?: TxDebugRecord(
            txid = normalizedTxid,
            localSubmittedPresent = false,
            localRecentlyFinalizedPresent = false,
            finalizedHistoryPresent = false,
            finalizedStatusConfirmed = false,
            visibleActivityCategory = TxVisibleActivityCategory.NONE,
            reservedBalanceContributor = false,
            suppressedAsDuplicate = false,
            suppressionReason = null,
            reconciliationSource = TxReconciliationSource.LOCAL_ONLY,
            lastObservedHeight = null,
            whyStillSubmitted = "tx_not_present_in_current_wallet_state",
            debugSummary = "This txid is not present in the wallet's current submitted, bridge, or finalized-history-backed activity state.",
        )
}

private fun buildPendingTransactionStates(
    finalizedUtxos: List<WalletUtxo>,
    submitted: List<SubmittedTransactionRecord>,
): List<PendingTransactionState> {
    val utxoValuesByOutPoint = finalizedUtxos.associate { utxo ->
        WalletOutPoint(
            txid = utxo.txid.lowercase(),
            vout = utxo.vout,
        ) to utxo.valueUnits
    }
    return submitted.map { record ->
        PendingTransactionState(
            txid = record.txid,
            recipientAddress = record.recipientAddress,
            amountUnits = record.amountUnits,
            appliedFeeUnits = record.appliedFeeUnits,
            totalDeductionUnits = record.amountUnits + record.appliedFeeUnits,
            reservedUnits = record.consumedInputs.sumOf { utxoValuesByOutPoint[it] ?: 0L },
            consumedInputCount = record.consumedInputs.size,
            createdAtEpochMillis = record.createdAtEpochMillis,
        )
    }
}

private fun buildHealthyOrDegradedEndpointHealth(
    activeEndpoint: RpcEndpoint,
    status: NetworkIdentity,
): EndpointHealthState {
    val degradedReasons = mutableListOf<String>()
    val finalizedLag = status.finalizedLag
    if (status.syncSnapshotPresent == false) {
        degradedReasons += "Sync snapshot is unavailable."
    }
    if (status.bootstrapSyncIncomplete == true) {
        degradedReasons += "Bootstrap sync is incomplete."
    }
    if (status.peerHeightDisagreement == true) {
        degradedReasons += "Peers disagree on height."
    }
    if (status.nextHeightCommitteeAvailable == false) {
        degradedReasons += "Next-height committee data is unavailable."
    }
    if (status.nextHeightProposerAvailable == false) {
        degradedReasons += "Next-height proposer data is unavailable."
    }
    if (status.observedNetworkHeightKnown == false) {
        degradedReasons += "Observed network height is unknown."
    }
    if (finalizedLag != null && finalizedLag > 2L) {
        degradedReasons += "Finalized lag is $finalizedLag blocks."
    }

    return if (degradedReasons.isEmpty()) {
        EndpointHealthState(
            activeEndpoint = activeEndpoint,
            resolvedExplorerUrl = activeEndpoint.url,
            resolvedRpcUrl = rpcUrlFromExplorerUrl(activeEndpoint.url),
            reachability = EndpointReachabilityState.REACHABLE,
            syncTrust = SyncTrustState.HEALTHY,
            status = status,
            summary = "Endpoint reachable and synced enough for normal use",
            detail = "The endpoint is on Finalis mainnet and reports finalized sync health suitable for normal wallet use.",
        )
    } else {
        EndpointHealthState(
            activeEndpoint = activeEndpoint,
            resolvedExplorerUrl = activeEndpoint.url,
            resolvedRpcUrl = rpcUrlFromExplorerUrl(activeEndpoint.url),
            reachability = EndpointReachabilityState.REACHABLE,
            syncTrust = SyncTrustState.DEGRADED,
            status = status,
            summary = "Endpoint reachable but degraded",
            detail = degradedReasons.joinToString(separator = " "),
        )
    }
}
