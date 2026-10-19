package com.finalis.mobile.app

import android.content.SharedPreferences
import com.finalis.mobile.core.model.AddressValidationResult
import com.finalis.mobile.core.model.BalanceSnapshot
import com.finalis.mobile.core.model.BroadcastResult
import com.finalis.mobile.core.model.HistoryEntry
import com.finalis.mobile.core.model.HistoryPageResult
import com.finalis.mobile.core.model.ImportedWalletRecord
import com.finalis.mobile.core.model.NetworkIdentity
import com.finalis.mobile.core.model.SubmittedTransactionRecord
import com.finalis.mobile.core.model.TxDetail
import com.finalis.mobile.core.model.TxDirection
import com.finalis.mobile.core.model.TxInput
import com.finalis.mobile.core.model.TxOutput
import com.finalis.mobile.core.model.TxStatus
import com.finalis.mobile.core.model.WalletAddress
import com.finalis.mobile.core.model.WalletOutPoint
import com.finalis.mobile.core.model.WalletProfile
import com.finalis.mobile.core.model.WalletUtxo
import com.finalis.mobile.core.storage.SubmittedTransactionStore
import com.finalis.mobile.core.storage.WalletStore
import com.finalis.mobile.core.wallet.SubmittedTransactionManager
import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.wallet.WalletSafetyManager
import com.finalis.mobile.core.wallet.WalletSessionManager
import com.finalis.mobile.data.lightserver.LightserverRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletViewModelRefreshTest {
    @Test
    fun `latest history bootstrap starts from newest finalized page instead of oldest page`() = runBlocking {
        val repository = LatestHistoryBootstrapRepository()

        val page = loadLatestHistoryBootstrapPage(
            repository = repository,
            address = "sc1wallet",
            finalizedHeight = 400L,
            limit = 3,
        )

        assertEquals(listOf(398L, 399L, 400L), page.items.map { it.height })
        assertEquals(2, repository.calls.size)
        assertEquals(145L, repository.calls.first().fromHeight)
    }

    @Test
    fun `resume reconciliation clears submitted state when backend already finalized`() = runBlocking {
        val fixture = walletViewModelFixture()
        fixture.viewModel.loadStartup()
        fixture.repository.finalizedTxids += FixtureTxid

        val refreshed = fixture.viewModel.refreshDashboardOnResume()

        val ready = fixture.viewModel.readState as DashboardState.Ready
        assertTrue(refreshed)
        assertEquals(emptyList(), ready.submitted)
        assertEquals(0L, ready.reservedUnits)
        assertEquals(listOf(FixtureTxid), ready.lifecycleState.recentlyFinalizedTransactions.map { it.txid })
        assertTrue(
            fixture.viewModel.diagnosticsState.txDebugRecords.any { record ->
                record.txid == FixtureTxid &&
                    record.visibleActivityCategory == TxVisibleActivityCategory.RECENTLY_FINALIZED &&
                    record.finalizedStatusConfirmed
            },
        )
    }

    @Test
    fun `manual refresh clears submitted state in same refresh cycle when backend already finalized`() = runBlocking {
        val fixture = walletViewModelFixture()
        fixture.viewModel.loadStartup()
        fixture.repository.finalizedTxids += FixtureTxid

        fixture.viewModel.refreshDashboard()

        val ready = fixture.viewModel.readState as DashboardState.Ready
        assertEquals(emptyList(), ready.submitted)
        assertEquals(0L, ready.reservedUnits)
        assertEquals(listOf(FixtureTxid), ready.lifecycleState.recentlyFinalizedTransactions.map { it.txid })
    }

    @Test
    fun `repeated resume and manual refreshes do not re-add submitted state or queue duplicate resume refreshes`() = runBlocking {
        val fixture = walletViewModelFixture(nowEpochMillis = mutableNow(10_000L))
        fixture.viewModel.loadStartup()
        fixture.repository.finalizedTxids += FixtureTxid

        val firstResume = fixture.viewModel.refreshDashboardOnResume()
        val secondResume = fixture.viewModel.refreshDashboardOnResume()
        fixture.viewModel.refreshDashboard()

        val ready = fixture.viewModel.readState as DashboardState.Ready
        assertTrue(firstResume)
        assertFalse(secondResume)
        assertEquals(emptyList(), ready.submitted)
        assertEquals(0L, ready.reservedUnits)
        assertEquals(4, fixture.repository.statusCalls)
    }

    @Test
    fun `resume refresh keeps tx submitted when backend has not finalized it`() = runBlocking {
        val fixture = walletViewModelFixture()
        fixture.viewModel.loadStartup()

        val refreshed = fixture.viewModel.refreshDashboardOnResume()

        val ready = fixture.viewModel.readState as DashboardState.Ready
        assertTrue(refreshed)
        assertEquals(listOf(FixtureTxid), ready.submitted.map { it.txid })
        assertEquals(70_000L, ready.reservedUnits)
        assertEquals(listOf(FixtureTxid), ready.lifecycleState.pendingTransactions.map { it.txid })
    }
}

private const val FixtureTxid = "46caef73d6282ed2418d4a9c6e84e112ac1c6458ebc7f125a5a4d08ec68096bd"

private data class WalletViewModelFixture(
    val viewModel: WalletViewModel,
    val repository: MutableRefreshRepository,
)

private fun walletViewModelFixture(
    nowEpochMillis: (() -> Long)? = null,
): WalletViewModelFixture {
    val repository = MutableRefreshRepository()
    val rpcSettingsRepository = RpcSettingsRepository(InMemorySharedPreferences())
    val walletSessionManager = WalletSessionManager(
        walletStore = TestWalletStore(walletRecord()),
    )
    val submittedTransactionManager = SubmittedTransactionManager(
        submittedTransactionStore = RefreshTestSubmittedTransactionStore(listOf(submittedRecord())),
    )
    val walletSafetyManager = WalletSafetyManager(
        walletSessionManager = walletSessionManager,
        submittedTransactionManager = submittedTransactionManager,
    )
    val viewModel = WalletViewModel(
        repository = repository,
        rpcSettingsRepository = rpcSettingsRepository,
        walletSessionManager = walletSessionManager,
        submittedTransactionManager = submittedTransactionManager,
        walletSafetyManager = walletSafetyManager,
        nowEpochMillis = nowEpochMillis ?: { 10_000L },
    )
    return WalletViewModelFixture(
        viewModel = viewModel,
        repository = repository,
    )
}

private fun mutableNow(initial: Long): () -> Long {
    var now = initial
    return { now }
}

private class MutableRefreshRepository : LightserverRepository {
    val finalizedTxids = linkedSetOf<String>()
    var statusCalls: Int = 0
        private set

    override suspend fun loadStatus(): NetworkIdentity {
        statusCalls += 1
        return NetworkIdentity(
            name = FinalisNetwork.MAINNET.name,
            networkId = FinalisNetwork.MAINNET.networkId,
            protocolVersion = 1,
            featureFlags = 1L,
            genesisHash = FinalisNetwork.MAINNET.genesisHash,
            tipHeight = 100L,
            tipHash = "f".repeat(64),
            serverTruth = "finalized_only",
            proofsTipOnly = true,
            finalizedHeight = 100L,
            finalizedHash = "f".repeat(64),
            syncMode = "finalized_only",
            syncSnapshotPresent = true,
            observedNetworkHeightKnown = true,
            finalizedLag = 0L,
        )
    }

    override suspend fun validateAddress(address: String): AddressValidationResult =
        AddressValidationResult(valid = true, normalizedAddress = address)

    override suspend fun loadBalance(address: String): BalanceSnapshot =
        BalanceSnapshot(
            address = WalletAddress(address),
            confirmedUnits = 100_000L,
            asset = "FINALIS",
            tipHeight = 100L,
            tipHash = "f".repeat(64),
        )

    override suspend fun loadUtxos(address: String): List<WalletUtxo> = listOf(
        WalletUtxo(
            txid = "b".repeat(64),
            vout = 0,
            valueUnits = 70_000L,
            height = 99L,
            scriptPubKeyHex = "76a914" + "11".repeat(20) + "88ac",
        ),
    )

    override suspend fun loadHistoryPage(address: String, cursor: String?, limit: Int, fromHeight: Long?): HistoryPageResult =
        HistoryPageResult(items = emptyList(), hasMore = false)

    override suspend fun loadHistory(address: String, cursor: String?, limit: Int, fromHeight: Long?): List<HistoryEntry> = emptyList()

    override suspend fun loadTxDetail(txid: String): TxDetail =
        TxDetail(
            txid = txid,
            height = 80L,
            status = TxStatus.FINALIZED,
            finalizedTransitionHash = "c4b7eb9457e5ecf581ea22e0fc39ea09735c5c3093bb1adc17c3b4c11e4f0de7",
            finalizedDepth = 31L,
            inputs = listOf(TxInput(prevTxid = "b".repeat(64), prevVout = 0)),
            outputs = listOf(TxOutput(index = 0, value = 50_000L, address = "sc1recipient")),
        )

    override suspend fun findFinalizedTxDetail(txid: String): TxDetail? =
        if (finalizedTxids.contains(txid.lowercase())) loadTxDetail(txid) else null

    override suspend fun broadcastTx(txHex: String): BroadcastResult =
        BroadcastResult(
            accepted = true,
            txid = FixtureTxid,
            status = TxStatus.SUBMITTED,
            errorCode = null,
            error = null,
        )
}

private class LatestHistoryBootstrapRepository : LightserverRepository {
    data class Call(val cursor: String?, val fromHeight: Long?)

    val calls = mutableListOf<Call>()

    override suspend fun loadStatus(): NetworkIdentity = throw UnsupportedOperationException()

    override suspend fun validateAddress(address: String): AddressValidationResult = throw UnsupportedOperationException()

    override suspend fun loadBalance(address: String): BalanceSnapshot = throw UnsupportedOperationException()

    override suspend fun loadUtxos(address: String): List<WalletUtxo> = emptyList()

    override suspend fun loadHistoryPage(address: String, cursor: String?, limit: Int, fromHeight: Long?): HistoryPageResult {
        calls += Call(cursor = cursor, fromHeight = fromHeight)
        return when (cursor) {
            null -> HistoryPageResult(
                items = listOf(
                    historyEntry(height = 395L, txid = "a".repeat(64)),
                    historyEntry(height = 396L, txid = "b".repeat(64)),
                    historyEntry(height = 397L, txid = "c".repeat(64)),
                ),
                nextCursor = """{"start_after":{"height":397,"txid":"${"c".repeat(64)}"},"from_height":145}""",
                hasMore = true,
            )

            else -> HistoryPageResult(
                items = listOf(
                    historyEntry(height = 398L, txid = "d".repeat(64)),
                    historyEntry(height = 399L, txid = "e".repeat(64)),
                    historyEntry(height = 400L, txid = "f".repeat(64)),
                ),
                nextCursor = null,
                hasMore = false,
            )
        }
    }

    override suspend fun loadHistory(address: String, cursor: String?, limit: Int, fromHeight: Long?): List<HistoryEntry> = emptyList()

    override suspend fun loadTxDetail(txid: String): TxDetail = throw UnsupportedOperationException()

    override suspend fun findFinalizedTxDetail(txid: String): TxDetail? = null

    override suspend fun broadcastTx(txHex: String): BroadcastResult = throw UnsupportedOperationException()
}

private class TestWalletStore(
    private var record: ImportedWalletRecord?,
) : WalletStore {
    override fun load(): ImportedWalletRecord? = record

    override fun save(record: ImportedWalletRecord) {
        this.record = record
    }

    override fun clear() {
        record = null
    }
}

private class RefreshTestSubmittedTransactionStore(
    initial: List<SubmittedTransactionRecord>,
) : SubmittedTransactionStore {
    private val records = linkedMapOf<String, SubmittedTransactionRecord>()

    init {
        initial.forEach { save(it) }
    }

    override fun list(): List<SubmittedTransactionRecord> = records.values.toList()

    override fun save(record: SubmittedTransactionRecord) {
        records[record.txid.lowercase()] = record.copy(txid = record.txid.lowercase())
    }

    override fun remove(txid: String) {
        records.remove(txid.lowercase())
    }

    override fun clear() {
        records.clear()
    }
}

private fun walletRecord(): ImportedWalletRecord =
    ImportedWalletRecord(
        privateKeyHex = "1".repeat(64),
        walletProfile = WalletProfile(
            address = WalletAddress("sc1wallet"),
            publicKeyHex = "2".repeat(64),
        ),
        importedAtEpochMillis = 1_000L,
    )

private fun submittedRecord(): SubmittedTransactionRecord =
    SubmittedTransactionRecord(
        txid = FixtureTxid,
        recipientAddress = "sc1recipient",
        amountUnits = 50_000L,
        requestedFeeUnits = 1_000L,
        appliedFeeUnits = 1_000L,
        changeUnits = 19_000L,
        createdAtEpochMillis = 1_000L,
        consumedInputs = listOf(WalletOutPoint(txid = "b".repeat(64), vout = 0)),
    )

private fun historyEntry(height: Long, txid: String): HistoryEntry =
    HistoryEntry(
        txid = txid,
        height = height,
        status = TxStatus.FINALIZED,
        direction = TxDirection.SEND,
        creditedValue = 0L,
        debitedValue = 1L,
        netValue = -1L,
    )

private class InMemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        ((values[key] as? Set<String>)?.toMutableSet() ?: defValues)

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = values?.toSet()
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = null
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clearAll = true
        }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            pending.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
