package com.finalis.mobile.core.wallet

import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.model.NetworkIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletNetworkGuardTest {
    @Test
    fun `accepts mainnet status for mainnet`() {
        assertNull(WalletNetworkGuard.detectMismatch(statusFor(FinalisNetwork.MAINNET), FinalisNetwork.MAINNET))
    }

    @Test
    fun `accepts testnet status for testnet`() {
        assertNull(WalletNetworkGuard.detectMismatch(statusFor(FinalisNetwork.TESTNET), FinalisNetwork.TESTNET))
    }

    @Test
    fun `accepts live testnet get_status identity`() {
        // Values reported by http://130.61.126.39:19544/rpc get_status.
        val status = statusFor(
            FinalisNetwork.TESTNET,
            networkId = "a6ffe3b32f81d1ad7df761693dc87dd8",
            genesisHash = "4faa7e1b8e79509c767f7f51f711eba7c75e9e9e4d9ea105fa020dd15cdd9d0c",
        )
        assertNull(WalletNetworkGuard.detectMismatch(status, FinalisNetwork.TESTNET))
    }

    @Test
    fun `rejects testnet endpoint for mainnet wallet`() {
        val mismatch = WalletNetworkGuard.detectMismatch(statusFor(FinalisNetwork.TESTNET), FinalisNetwork.MAINNET)

        assertEquals("invalid_network_name", mismatch?.code)
        assertTrue(mismatch!!.message.contains("mainnet"))
    }

    @Test
    fun `rejects mainnet endpoint for testnet wallet`() {
        val mismatch = WalletNetworkGuard.detectMismatch(statusFor(FinalisNetwork.MAINNET), FinalisNetwork.TESTNET)

        assertEquals("invalid_network_name", mismatch?.code)
        assertTrue(mismatch!!.message.contains("testnet"))
    }

    @Test
    fun `rejects wrong network id`() {
        for (network in FinalisNetwork.ALL) {
            val mismatch = WalletNetworkGuard.detectMismatch(statusFor(network, networkId = "ff".repeat(16)), network)
            assertEquals("invalid_network_id", mismatch?.code)
            assertTrue(mismatch!!.message.contains(network.name))
        }
    }

    @Test
    fun `rejects testnet-named endpoint carrying mainnet network id`() {
        val mismatch = WalletNetworkGuard.detectMismatch(
            statusFor(FinalisNetwork.TESTNET, networkId = FinalisNetwork.MAINNET.networkId),
            FinalisNetwork.TESTNET,
        )
        assertEquals("invalid_network_id", mismatch?.code)
    }

    @Test
    fun `rejects wrong testnet genesis hash`() {
        val mismatch = WalletNetworkGuard.detectMismatch(
            statusFor(FinalisNetwork.TESTNET, genesisHash = "aa".repeat(32)),
            FinalisNetwork.TESTNET,
        )

        assertEquals("invalid_genesis_hash", mismatch?.code)
        assertTrue(mismatch!!.message.contains("testnet"))
    }

    @Test
    fun `mainnet genesis is not pinned until published`() {
        assertNull(FinalisNetwork.MAINNET.genesisHash)
        val mismatch = WalletNetworkGuard.detectMismatch(
            statusFor(FinalisNetwork.MAINNET, genesisHash = "aa".repeat(32)),
            FinalisNetwork.MAINNET,
        )
        assertNull(mismatch)
    }

    private fun statusFor(
        network: FinalisNetwork,
        networkId: String = network.networkId,
        genesisHash: String? = network.genesisHash ?: "bb".repeat(32),
    ) = NetworkIdentity(
        name = network.name,
        networkId = networkId,
        protocolVersion = 1,
        featureFlags = 1L,
        genesisHash = genesisHash,
        tipHeight = 1L,
        tipHash = "11".repeat(32),
        serverTruth = "finalized_only",
        proofsTipOnly = true,
    )
}
