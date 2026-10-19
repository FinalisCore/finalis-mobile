package com.finalis.mobile.core.wallet

import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.model.ImportedWalletRecord
import com.finalis.mobile.core.model.SubmittedTransactionRecord
import com.finalis.mobile.core.storage.SubmittedTransactionStore
import com.finalis.mobile.core.storage.WalletStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalletSafetyManagerTest {
    @Test
    fun `reset clears wallet and submitted transaction state`() {
        val walletStore = SafetyInMemoryWalletStore()
        val submittedStore = SafetyInMemorySubmittedTransactionStore()
        val sessionManager = WalletSessionManager(walletStore, nowEpochMillis = { 7L })
        val submittedManager = SubmittedTransactionManager(submittedStore)
        val safetyManager = WalletSafetyManager(sessionManager, submittedManager)

        sessionManager.importAndPersist(
            privateKeyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            network = FinalisNetwork.TESTNET,
        )
        submittedStore.save(
            SubmittedTransactionRecord(
                txid = "ab".repeat(32),
                recipientAddress = "tsc1recipient",
                amountUnits = 10L,
                requestedFeeUnits = 1L,
                appliedFeeUnits = 1L,
                changeUnits = 0L,
                createdAtEpochMillis = 99L,
            )
        )

        safetyManager.resetWallet()

        assertNull(walletStore.load())
        assertEquals(emptyList(), submittedStore.list())
    }
}

private class SafetyInMemoryWalletStore : WalletStore {
    private var record: ImportedWalletRecord? = null

    override fun load(): ImportedWalletRecord? = record

    override fun save(record: ImportedWalletRecord) {
        this.record = record
    }

    override fun clear() {
        record = null
    }
}

private class SafetyInMemorySubmittedTransactionStore : SubmittedTransactionStore {
    private val records = linkedMapOf<String, SubmittedTransactionRecord>()

    override fun list(): List<SubmittedTransactionRecord> = records.values.toList()

    override fun save(record: SubmittedTransactionRecord) {
        records[record.txid.lowercase()] = record
    }

    override fun remove(txid: String) {
        records.remove(txid.lowercase())
    }

    override fun clear() {
        records.clear()
    }
}
