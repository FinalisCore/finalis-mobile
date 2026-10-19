package com.finalis.mobile.core.wallet

import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.model.ImportedWalletRecord
import com.finalis.mobile.core.storage.WalletStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WalletSessionManagerTest {
    @Test
    fun `imports valid private key and persists wallet`() {
        val walletStore = InMemoryWalletStore()
        val manager = WalletSessionManager(walletStore, nowEpochMillis = { 1234L })

        val record = manager.importAndPersist(
            privateKeyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            network = FinalisNetwork.MAINNET,
        )

        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", record.privateKeyHex)
        assertEquals("sc1adrqzotqb6xl76opjawtlcn5ds6msa6sqaw7dj7p", record.walletProfile.address.value)
        assertEquals(FinalisNetwork.MAINNET, record.walletProfile.network)
        assertEquals(record, walletStore.load())
    }

    @Test
    fun `imports testnet wallet with tsc address`() {
        val walletStore = InMemoryWalletStore()
        val manager = WalletSessionManager(walletStore, nowEpochMillis = { 1234L })

        val record = manager.importAndPersist(
            privateKeyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            network = FinalisNetwork.TESTNET,
        )

        assertEquals("tsc1adrqzotqb6xl76opjawtlcn5ds6msa6sqdtecmte", record.walletProfile.address.value)
        assertEquals(FinalisNetwork.TESTNET, record.walletProfile.network)
        assertEquals(record, walletStore.load())
    }

    @Test
    fun `rebinding network re-derives address for the same key`() {
        val walletStore = InMemoryWalletStore()
        val manager = WalletSessionManager(walletStore, nowEpochMillis = { 7L })
        val mainnet = manager.importAndPersist(
            privateKeyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            network = FinalisNetwork.MAINNET,
        )

        val testnet = manager.rebindNetwork(mainnet, FinalisNetwork.TESTNET)

        assertEquals(mainnet.privateKeyHex, testnet.privateKeyHex)
        assertEquals(mainnet.walletProfile.publicKeyHex, testnet.walletProfile.publicKeyHex)
        assertEquals("tsc1adrqzotqb6xl76opjawtlcn5ds6msa6sqdtecmte", testnet.walletProfile.address.value)
        assertEquals(FinalisNetwork.TESTNET, testnet.walletProfile.network)
        assertEquals(testnet, walletStore.load())
    }

    @Test
    fun `rejects invalid import without mutating store`() {
        val walletStore = InMemoryWalletStore()
        val manager = WalletSessionManager(walletStore)

        assertFailsWith<IllegalArgumentException> {
            manager.importAndPersist(
                privateKeyHex = "xyz",
                network = FinalisNetwork.MAINNET,
            )
        }
        assertNull(walletStore.load())
    }

    @Test
    fun `loads persisted wallet on startup`() {
        val walletStore = InMemoryWalletStore()
        val firstManager = WalletSessionManager(walletStore, nowEpochMillis = { 42L })
        val saved = firstManager.importAndPersist(
            privateKeyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            network = FinalisNetwork.TESTNET,
        )

        val secondManager = WalletSessionManager(walletStore)

        assertEquals(saved, secondManager.loadCurrentWallet())
    }

    @Test
    fun `exports normalized private key hex`() {
        val walletStore = InMemoryWalletStore()
        val manager = WalletSessionManager(walletStore)
        val record = manager.importAndPersist(
            privateKeyHex = " 000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F ",
            network = FinalisNetwork.MAINNET,
        )

        assertEquals(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
            manager.exportPrivateKeyHex(record),
        )
    }

}

private class InMemoryWalletStore : WalletStore {
    private var record: ImportedWalletRecord? = null

    override fun load(): ImportedWalletRecord? = record

    override fun save(record: ImportedWalletRecord) {
        this.record = record
    }

    override fun clear() {
        record = null
    }
}
