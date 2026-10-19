package com.finalis.mobile.data.lightserver

import com.finalis.mobile.core.crypto.FinalisAddressCodec
import com.finalis.mobile.core.crypto.FinalisKeyDerivation
import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.model.ImportedWalletRecord
import com.finalis.mobile.core.model.WalletAddress
import com.finalis.mobile.core.model.WalletProfile
import com.finalis.mobile.core.wallet.WalletNetworkGuard
import com.finalis.mobile.core.wallet.WalletSendService
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Talks to the public testnet lightserver. Skipped unless FINALIS_LIVE_TESTNET=1, so the regular
 * build stays offline: `FINALIS_LIVE_TESTNET=1 ./gradlew :data:lightserver:testDebugUnitTest`.
 */
class LiveTestnetSmokeTest {
    private val enabled = System.getenv("FINALIS_LIVE_TESTNET") == "1"
    private val repository = LiveLightserverRepository(
        transport = UrlConnectionLightserverTransport(
            rpcUrl = System.getenv("FINALIS_TESTNET_RPC") ?: FinalisNetwork.TESTNET.defaultEndpoints.first(),
            connectTimeoutMs = 15_000,
            readTimeoutMs = 15_000,
        ),
    )

    @Test
    fun `live get_status passes the testnet guard and fails the mainnet guard`() = liveTest {
        val status = repository.loadStatus()

        assertEquals("testnet", status.name)
        assertEquals(FinalisNetwork.TESTNET.genesisHash, status.genesisHash)
        assertNull(WalletNetworkGuard.detectMismatch(status, FinalisNetwork.TESTNET))
        assertNotNull(WalletNetworkGuard.detectMismatch(status, FinalisNetwork.MAINNET))
    }

    @Test
    fun `live balance utxos and history load for a tsc1 address`() = liveTest {
        val address = System.getenv("FINALIS_TESTNET_ADDRESS") ?: BootstrapValidatorPayoutAddress
        val validation = repository.validateAddress(address)
        assertTrue(validation.valid)
        assertEquals(true, validation.serverNetworkMatch)

        val balance = repository.loadBalance(address)
        val utxos = repository.loadUtxos(address)
        val history = repository.loadHistoryPage(address, cursor = null, limit = 20, fromHeight = null)

        assertEquals(utxos.sumOf { it.valueUnits }, balance.confirmedUnits)
        println("testnet $address: balance=${balance.confirmedUnits} utxos=${utxos.size} history=${history.items.size} tip=${balance.tipHeight}")
    }

    @Test
    fun `live rejects a mainnet address`() = liveTest {
        val validation = repository.validateAddress("sc1adrqzotqb6xl76opjawtlcn5ds6msa6sqaw7dj7p")
        assertEquals(false, validation.serverNetworkMatch)
    }

    /**
     * Broadcasts one self-transfer with the app's send path. Needs a funded key:
     * FINALIS_LIVE_TESTNET=1 FINALIS_TESTNET_SEND_PRIVKEY=<hex32> [FINALIS_TESTNET_SEND_TO=tsc1…]
     */
    @Test
    fun `live send broadcasts and finalizes a testnet transfer`() = liveTest {
        val privateKeyHex = System.getenv("FINALIS_TESTNET_SEND_PRIVKEY") ?: return@liveTest
        val keyMaterial = FinalisKeyDerivation.deriveKeyMaterial(privateKeyHex)
        val address = FinalisAddressCodec.deriveAddressFromPublicKey(keyMaterial.publicKeyHex, FinalisNetwork.TESTNET.hrp)
        val wallet = ImportedWalletRecord(
            privateKeyHex = keyMaterial.privateKeyHex,
            walletProfile = WalletProfile(WalletAddress(address), keyMaterial.publicKeyHex, network = FinalisNetwork.TESTNET),
            importedAtEpochMillis = 0L,
        )
        val utxos = repository.loadUtxos(address)
        check(utxos.isNotEmpty()) { "$address has no finalized UTXOs" }
        val built = WalletSendService().buildAndSignTransaction(
            wallet = wallet,
            spendableUtxos = utxos,
            recipientAddress = System.getenv("FINALIS_TESTNET_SEND_TO") ?: address,
            amountUnits = 10_000L,
        )
        val result = repository.broadcastTx(built.txHex)
        println("broadcast ${built.txid}: accepted=${result.accepted} code=${result.errorCode} error=${result.error}")
        assertTrue(result.accepted, "broadcast rejected: ${result.errorCode} ${result.error}")
        assertEquals(built.txid, result.txid)

        val deadline = System.currentTimeMillis() + 15 * 60_000L
        var detail = repository.findFinalizedTxDetail(built.txid)
        while (detail == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10_000L)
            detail = repository.findFinalizedTxDetail(built.txid)
        }
        assertNotNull(detail, "tx ${built.txid} did not finalize within 15 minutes")
        println("finalized ${built.txid} at height ${detail.height}")
    }

    private fun liveTest(block: suspend () -> Unit) {
        if (!enabled) return
        var failure: Throwable? = null
        block.startCoroutine(
            object : Continuation<Unit> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<Unit>) {
                    failure = result.exceptionOrNull()
                }
            },
        )
        failure?.let { throw it }
    }

    private companion object {
        // finalis-cli address_from_pubkey --hrp tsc --pubkey <testnet genesis validator 150cf9f3…fc24>
        const val BootstrapValidatorPayoutAddress = "tsc1abxqw7c2ahg7vgx66dudimzstauxokoqgwz4tbbp"
    }
}
