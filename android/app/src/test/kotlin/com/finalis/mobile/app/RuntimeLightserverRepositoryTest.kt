package com.finalis.mobile.app

import com.finalis.mobile.core.model.AddressValidationResult
import com.finalis.mobile.core.model.BalanceSnapshot
import com.finalis.mobile.core.model.BroadcastResult
import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.model.HistoryEntry
import com.finalis.mobile.core.model.HistoryPageResult
import com.finalis.mobile.core.model.NetworkIdentity
import com.finalis.mobile.core.model.TxDetail
import com.finalis.mobile.core.model.TxStatus
import com.finalis.mobile.core.model.WalletAddress
import com.finalis.mobile.core.model.WalletUtxo
import com.finalis.mobile.data.lightserver.LightserverDataException
import com.finalis.mobile.data.lightserver.LightserverRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class RuntimeLightserverRepositoryTest {
    @Test
    fun `normalize endpoint url accepts explorer base urls`() {
        assertEquals(
            "https://lightserver.finalis.org",
            normalizeRpcUrl("https://lightserver.finalis.org", FinalisNetwork.MAINNET),
        )
        assertEquals(
            "http://127.0.0.1:18080",
            normalizeRpcUrl("http://127.0.0.1:19444/", FinalisNetwork.MAINNET),
        )
    }

    @Test
    fun `normalize endpoint url migrates legacy rpc urls to explorer format`() {
        // Port 19444/rpc → port 18080
        assertEquals(
            "http://127.0.0.1:18080",
            normalizeRpcUrl("http://127.0.0.1:19444/rpc", FinalisNetwork.MAINNET),
        )
        // Non-19444 rpc path → strip /rpc, keep port
        assertEquals(
            "https://lightserver.finalis.org",
            normalizeRpcUrl("https://lightserver.finalis.org/rpc", FinalisNetwork.MAINNET),
        )
        // Full API paths should be normalized to base explorer endpoint
        assertEquals(
            "http://64.23.244.126:18080",
            normalizeRpcUrl("http://64.23.244.126:18080/api/status", FinalisNetwork.MAINNET),
        )
    }

    @Test
    fun `failover only skips unavailable endpoint and promotes first healthy endpoint`() = runBlocking {
        val unavailable = RpcEndpoint("https://unavailable.example/rpc")
        val healthy = RpcEndpoint("https://healthy.example/rpc")
        val store = FakeRpcEndpointSettingsStore(
            PersistedRpcSettings(
                savedEndpoints = listOf(unavailable, healthy),
                activeEndpoint = unavailable,
            ),
        )
        val repository = RuntimeLightserverRepository(
            endpointSettingsStore = store,
            repositoryFactory = { endpoint, _ ->
                when (endpoint.url) {
                    unavailable.url -> FakeLightserverRepository(
                        statusError = LightserverDataException("Lightserver unavailable"),
                    )

                    healthy.url -> FakeLightserverRepository(
                        status = sampleStatus(),
                        balance = BalanceSnapshot(
                            address = WalletAddress("sc1test"),
                            confirmedUnits = 42L,
                            asset = "FINALIS",
                            tipHeight = 99,
                            tipHash = "abcd",
                        ),
                    )

                    else -> fail("Unexpected endpoint ${endpoint.url}")
                }
            },
        )

        val balance = repository.loadBalance("sc1test")

        assertEquals(42L, balance.confirmedUnits)
        assertEquals(healthy, store.settings.activeEndpoint)
    }

    @Test
    fun `reachable mismatched endpoint is not silently skipped during reads`() = runBlocking {
        val wrong = RpcEndpoint("https://wrong.example/rpc")
        val healthy = RpcEndpoint("https://healthy.example/rpc")
        val store = FakeRpcEndpointSettingsStore(
            PersistedRpcSettings(
                savedEndpoints = listOf(wrong, healthy),
                activeEndpoint = wrong,
            ),
        )
        val repository = RuntimeLightserverRepository(
            endpointSettingsStore = store,
            repositoryFactory = { endpoint, _ ->
                when (endpoint.url) {
                    wrong.url -> FakeLightserverRepository(
                        status = sampleStatus(networkId = "wrong-network"),
                    )

                    healthy.url -> FakeLightserverRepository(
                        status = sampleStatus(),
                        balance = BalanceSnapshot(
                            address = WalletAddress("sc1test"),
                            confirmedUnits = 42L,
                            asset = "FINALIS",
                            tipHeight = 99,
                            tipHash = "abcd",
                        ),
                    )

                    else -> fail("Unexpected endpoint ${endpoint.url}")
                }
            },
        )

        val error = assertFailsWith<LightserverDataException> {
            repository.loadBalance("sc1test")
        }

        assertTrue(error.message.orEmpty().contains("not Finalis mainnet"))
        assertEquals(wrong, store.settings.activeEndpoint)
    }

    @Test
    fun `load status returns mismatched status for explicit network mismatch handling`() = runBlocking {
        val endpoint = RpcEndpoint("https://wrong.example/rpc")
        val store = FakeRpcEndpointSettingsStore(
            PersistedRpcSettings(
                savedEndpoints = listOf(endpoint),
                activeEndpoint = endpoint,
            ),
        )
        val repository = RuntimeLightserverRepository(
            endpointSettingsStore = store,
            repositoryFactory = { _, _ ->
                FakeLightserverRepository(
                    status = sampleStatus(networkId = "wrong-network"),
                )
            },
        )

        val status = repository.loadStatus()

        assertEquals("wrong-network", status.networkId)
    }
}

class RuntimeLightserverRepositoryTestnetTest {
    @Test
    fun `testnet endpoint urls keep the lightserver rpc path`() {
        assertEquals(
            "http://130.61.126.39:19544/rpc",
            normalizeRpcUrl("http://130.61.126.39:19544/rpc/", FinalisNetwork.TESTNET),
        )
        assertEquals(
            "http://130.61.126.39:19544/rpc",
            normalizeRpcUrl("http://130.61.126.39:19544", FinalisNetwork.TESTNET),
        )
    }

    @Test
    fun `testnet store validates endpoints against testnet and passes the network to the factory`() = runBlocking {
        val endpoint = RpcEndpoint("http://130.61.126.39:19544/rpc")
        val store = FakeRpcEndpointSettingsStore(
            PersistedRpcSettings(
                savedEndpoints = listOf(endpoint),
                activeEndpoint = endpoint,
                network = FinalisNetwork.TESTNET,
            ),
        )
        val seenNetworks = mutableListOf<FinalisNetwork>()
        val repository = RuntimeLightserverRepository(
            endpointSettingsStore = store,
            repositoryFactory = { _, network ->
                seenNetworks += network
                FakeLightserverRepository(
                    status = sampleStatus(FinalisNetwork.TESTNET),
                    balance = BalanceSnapshot(
                        address = WalletAddress("tsc1test"),
                        confirmedUnits = 7L,
                        asset = "FINALIS",
                        tipHeight = 99,
                        tipHash = "abcd",
                    ),
                )
            },
        )

        assertEquals(7L, repository.loadBalance("tsc1test").confirmedUnits)
        assertTrue(seenNetworks.isNotEmpty() && seenNetworks.all { it == FinalisNetwork.TESTNET })
    }

    @Test
    fun `mainnet endpoint is rejected for a testnet wallet`() = runBlocking {
        val endpoint = RpcEndpoint("https://mainnet.example")
        val store = FakeRpcEndpointSettingsStore(
            PersistedRpcSettings(
                savedEndpoints = listOf(endpoint),
                activeEndpoint = endpoint,
                network = FinalisNetwork.TESTNET,
            ),
        )
        val repository = RuntimeLightserverRepository(
            endpointSettingsStore = store,
            repositoryFactory = { _, _ -> FakeLightserverRepository(status = sampleStatus(FinalisNetwork.MAINNET)) },
        )

        val error = assertFailsWith<LightserverDataException> { repository.loadBalance("tsc1test") }
        assertTrue(error.message.orEmpty().contains("testnet"))
    }
}

private class FakeRpcEndpointSettingsStore(
    var settings: PersistedRpcSettings,
) : RpcEndpointSettingsStore {
    override fun loadSettings(): PersistedRpcSettings = settings

    override fun setActiveEndpoint(endpoint: RpcEndpoint) {
        settings = PersistedRpcSettings(
            savedEndpoints = listOf(endpoint) + settings.savedEndpoints.filterNot { it.url == endpoint.url },
            activeEndpoint = endpoint,
            network = settings.network,
        )
    }
}

private class FakeLightserverRepository(
    private val status: NetworkIdentity = sampleStatus(),
    private val balance: BalanceSnapshot = BalanceSnapshot(
        address = WalletAddress("sc1test"),
        confirmedUnits = 0L,
        asset = "FINALIS",
        tipHeight = status.tipHeight,
        tipHash = status.tipHash,
    ),
    private val statusError: RuntimeException? = null,
) : LightserverRepository {
    override suspend fun loadStatus(): NetworkIdentity = statusError?.let { throw it } ?: status

    override suspend fun validateAddress(address: String): AddressValidationResult =
        AddressValidationResult(
            valid = true,
            normalizedAddress = address,
            scriptPubKeyHex = "76a914" + "00".repeat(20) + "88ac",
            scriptHashHex = "11".repeat(32),
            serverNetworkMatch = true,
        )

    override suspend fun loadBalance(address: String): BalanceSnapshot = balance

    override suspend fun loadUtxos(address: String): List<WalletUtxo> = emptyList()

    override suspend fun loadHistoryPage(address: String, cursor: String?, limit: Int, fromHeight: Long?): HistoryPageResult =
        HistoryPageResult(items = emptyList(), hasMore = false)

    override suspend fun loadHistory(address: String, cursor: String?, limit: Int, fromHeight: Long?): List<HistoryEntry> = emptyList()

    override suspend fun loadTxDetail(txid: String): TxDetail {
        throw UnsupportedOperationException("Not used in this test")
    }

    override suspend fun findFinalizedTxDetail(txid: String): TxDetail? = null

    override suspend fun broadcastTx(txHex: String): BroadcastResult =
        BroadcastResult(
            accepted = true,
            txid = "abcd",
            status = TxStatus.SUBMITTED,
            errorCode = null,
            error = null,
        )
}

private fun sampleStatus(
    network: FinalisNetwork = FinalisNetwork.MAINNET,
    networkId: String = network.networkId,
): NetworkIdentity =
    NetworkIdentity(
        name = network.name,
        networkId = networkId,
        protocolVersion = 1,
        featureFlags = 1L,
        genesisHash = network.genesisHash,
        tipHeight = 99,
        tipHash = "abcd",
        serverTruth = "finalized_only",
        proofsTipOnly = true,
    )
