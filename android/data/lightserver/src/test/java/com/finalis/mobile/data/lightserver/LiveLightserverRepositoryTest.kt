package com.finalis.mobile.data.lightserver

import com.finalis.mobile.core.model.TxDirection
import com.finalis.mobile.core.model.TxStatus
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LiveLightserverRepositoryTest {
    @Test
    fun `loads status from current lightserver rpc shape`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(mapOf("get_status" to MockLightserverJson.status)),
        )

        val status = repository.loadStatus()

        assertEquals("mainnet", status.name)
        assertEquals(12345L, status.tipHeight)
        assertEquals(MockLightserverJson.KnownTransitionHash, status.tipHash)
        assertEquals("normal", status.checkpointDerivationMode)
        assertEquals(31L, status.qualifiedDepth)
    }

    @Test
    fun `validate address uses live contract fields`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(mapOf("validate_address" to MockLightserverJson.validateAddress)),
        )

        val validation = repository.validateAddress(MockLightserverJson.KnownWalletAddress)

        assertTrue(validation.valid)
        assertEquals(MockLightserverJson.KnownWalletAddress, validation.normalizedAddress)
        assertEquals(true, validation.serverNetworkMatch)
    }

    @Test
    fun `loads balance from finalized utxos after validating address`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(
                mapOf(
                    "get_status" to MockLightserverJson.status,
                    "validate_address" to MockLightserverJson.validateAddress,
                    "get_utxos" to MockLightserverJson.utxos,
                ),
            ),
        )

        val balance = repository.loadBalance(MockLightserverJson.KnownWalletAddress)

        assertEquals(500_000_000L, balance.confirmedUnits)
        assertEquals("FINALIS", balance.asset)
    }

    @Test
    fun `loads history from get_history_page contract`() = suspendTest {
        val transport = FakeTransport(
            mapOf(
                "validate_address" to MockLightserverJson.validateAddress,
                "get_history_page" to MockLightserverJson.historyPage,
                "get_tx" to MockLightserverJson.tx,
            ),
        )
        val repository = LiveLightserverRepository(transport = transport)

        val history = repository.loadHistory(MockLightserverJson.KnownWalletAddress, limit = 25)

        assertEquals(1, history.size)
        assertEquals(500_000_000L, history.first().netValue)
        assertTrue(transport.bodies.any { it.contains("\"method\":\"get_history_page\"") })
        assertTrue(transport.bodies.any { it.contains("\"limit\":25") })
    }

    @Test
    fun `loads paged history cursor and no-more-pages state`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(
                mapOf(
                    "validate_address" to MockLightserverJson.validateAddress,
                    "get_history_page" to """
                        {"jsonrpc":"2.0","id":1,"result":{"items":[{"txid":"${"a".repeat(64)}","height":10}],"has_more":true,"next_start_after":{"height":10,"txid":"${"a".repeat(64)}"}}}
                    """.trimIndent(),
                    "get_tx" to MockLightserverJson.tx,
                ),
            ),
        )

        val page = repository.loadHistoryPage(MockLightserverJson.KnownWalletAddress)

        assertEquals(1, page.items.size)
        assertEquals(true, page.hasMore)
        assertTrue(page.nextCursor?.contains("\"height\":10") == true)
    }

    @Test
    fun `loads tx detail from finalized tx status and tx payload`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(
                mapOf(
                    "get_tx_status" to MockLightserverJson.txStatus,
                    "get_tx" to MockLightserverJson.tx,
                    "validate_address" to MockLightserverJson.validateAddress,
                ),
            ),
        )

        val detail = repository.loadTxDetail("c".repeat(64))

        assertEquals(MockLightserverJson.KnownTransitionHash, detail.finalizedTransitionHash)
        assertEquals(6L, detail.finalizedDepth)
        assertEquals(1, detail.outputs.size)
        assertEquals(MockLightserverJson.KnownWalletAddress, detail.outputs.first().address)
    }

    @Test
    fun `history resolves settlement payouts that get_tx does not know`() = suspendTest {
        val transport = FakeTransport(
            mapOf(
                "validate_address" to MockLightserverJson.validateAddress,
                "get_history_page" to MockLightserverJson.historyPage,
                "get_tx" to NotFoundRpcError,
                "get_settlement" to settlementJson(),
            ),
        )
        val repository = LiveLightserverRepository(transport = transport)

        val history = repository.loadHistory(MockLightserverJson.KnownWalletAddress, limit = 25)

        assertEquals(1, history.size)
        assertEquals(TxDirection.RECEIVE, history.first().direction)
        assertEquals(700_000_000L, history.first().netValue)
        assertTrue(transport.bodies.any { it.contains("\"method\":\"get_settlement\"") })
    }

    @Test
    fun `tx detail falls back to get_settlement for a reward payout`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(
                mapOf(
                    "get_tx_status" to """{"jsonrpc":"2.0","id":1,"result":{"txid":"${"e".repeat(64)}","status":"not_found","finalized":false,"finalized_depth":0,"credit_safe":false}}""",
                    "get_settlement" to settlementJson(),
                    "validate_address" to MockLightserverJson.validateAddress,
                ),
            ),
        )

        val detail = repository.loadTxDetail("e".repeat(64))

        assertEquals(42L, detail.height)
        assertEquals(TxStatus.FINALIZED, detail.status)
        assertTrue(detail.inputs.isEmpty())
        assertEquals(700_000_000L, detail.outputs.single().value)
        assertEquals(MockLightserverJson.KnownWalletAddress, detail.outputs.single().address)
    }

    @Test
    fun `find finalized tx detail returns null when tx status reports not found`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(
                mapOf(
                    "get_tx_status" to """{"jsonrpc":"2.0","id":1,"result":{"txid":"${"c".repeat(64)}","status":"not_found","finalized":false,"finalized_depth":0,"credit_safe":false}}""",
                ),
            ),
        )

        val result = repository.findFinalizedTxDetail("c".repeat(64))

        assertEquals(null, result)
    }

    @Test
    fun `status parsing rejects stale backend missing finalized transition fields`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(
                mapOf(
                    "get_status" to """
                        {"jsonrpc":"2.0","id":1,"result":{"network_name":"mainnet","protocol_version":1,"feature_flags":1,"network_id":"a57ab83946712672c507b1bd312c5fb2","genesis_hash":"${"5".repeat(64)}","tip":{"height":1,"transition_hash":"${"a".repeat(64)}"},"sync":{"mode":"finalized_only"}}}
                    """.trimIndent(),
                ),
            ),
        )

        assertFailsWith<LightserverBackendIncompatibleException> {
            repository.loadStatus()
        }
    }

    @Test
    fun `validate address rejects incomplete valid response`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(
                mapOf(
                    "validate_address" to """
                        {"jsonrpc":"2.0","id":1,"result":{"valid":true,"normalized_address":"${MockLightserverJson.KnownWalletAddress}"}}
                    """.trimIndent(),
                ),
            ),
        )

        assertFailsWith<LightserverBackendIncompatibleException> {
            repository.validateAddress(MockLightserverJson.KnownWalletAddress)
        }
    }

    @Test
    fun `address-driven lookups stop on wrong-network validate response`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(
                mapOf(
                    "get_status" to MockLightserverJson.status,
                    "validate_address" to """
                        {"jsonrpc":"2.0","id":1,"result":{"valid":true,"normalized_address":"${MockLightserverJson.KnownWalletAddress}","hrp":"tsc","network_hint":"testnet","server_network_hrp":"sc","server_network_match":false,"addr_type":"p2pkh","pubkey_hash_hex":"00","script_pubkey_hex":"76a914${"00".repeat(20)}88ac","scripthash_hex":"${"11".repeat(32)}","error":null}}
                    """.trimIndent(),
                ),
            ),
        )

        assertFailsWith<LightserverAddressException> {
            repository.loadBalance(MockLightserverJson.KnownWalletAddress)
        }
    }

    @Test
    fun `history page rejects has more without next cursor`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(
                mapOf(
                    "validate_address" to MockLightserverJson.validateAddress,
                    "get_history_page" to """
                        {"jsonrpc":"2.0","id":1,"result":{"items":[],"has_more":true,"next_start_after":null}}
                    """.trimIndent(),
                ),
            ),
        )

        assertFailsWith<LightserverBackendIncompatibleException> {
            repository.loadHistoryPage(MockLightserverJson.KnownWalletAddress)
        }
    }

    @Test
    fun `tx status rejects malformed finalized payload`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(
                mapOf(
                    "get_tx_status" to """
                        {"jsonrpc":"2.0","id":1,"result":{"txid":"${"c".repeat(64)}","status":"finalized","finalized":true,"height":12,"finalized_depth":3}}
                    """.trimIndent(),
                ),
            ),
        )

        assertFailsWith<LightserverBackendIncompatibleException> {
            repository.findFinalizedTxDetail("c".repeat(64))
        }
    }

    @Test
    fun `broadcast result normalizes direct lightserver rejection strings`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(
                mapOf(
                    "broadcast_tx" to """
                        {"jsonrpc":"2.0","id":1,"result":{"accepted":false,"txid":"${"f".repeat(64)}","error":"tx parse failed"}}
                    """.trimIndent(),
                ),
            ),
        )

        val result = repository.broadcastTx("aa")

        assertTrue(!result.accepted)
        assertEquals("TX_PARSE_FAILED", result.errorCode?.name)
    }

    @Test
    fun `broadcast result preserves structured lightserver relay error code`() = suspendTest {
        val repository = LiveLightserverRepository(
            transport = FakeTransport(
                mapOf(
                    "broadcast_tx" to """
                        {"jsonrpc":"2.0","id":1,"result":{"accepted":false,"txid":"${"f".repeat(64)}","error":"connect relay peer failed","error_code":"relay_unavailable","error_message":"connect relay peer failed"}}
                    """.trimIndent(),
                ),
            ),
        )

        val result = repository.broadcastTx("aa")

        assertTrue(!result.accepted)
        assertEquals("RELAY_UNAVAILABLE", result.errorCode?.name)
        assertEquals("connect relay peer failed", result.error)
    }

    @Test
    fun `transport sends json rpc requests`() = suspendTest {
        val transport = FakeTransport(mapOf("get_status" to MockLightserverJson.status))
        val repository = LiveLightserverRepository(transport)

        val status = repository.loadStatus()

        assertNotNull(transport.lastBody)
        assertTrue(transport.lastBody!!.contains("\"method\":\"get_status\""))
        assertEquals("mainnet", status.name)
    }
}

private const val NotFoundRpcError = """{"jsonrpc":"2.0","id":1,"error":{"code":-32001,"message":"not found"}}"""

// A reward payout of 7 FLS to the mock wallet's script (MockLightserverJson.validateAddress).
private fun settlementJson(): String =
    """{"jsonrpc":"2.0","id":1,"result":{"txid":"${"e".repeat(64)}","height":42,"transition_hash":"${"d".repeat(64)}","decoded_outputs":[{"amount":700000000,"script_hex":"76a914e30cba700faebff9cf482d3589bd1cbcc903d28088ac","address":"${MockLightserverJson.KnownWalletAddress}","decoded_kind":null}]}}"""

private class FakeTransport(
    private val responses: Map<String, String>,
) : LightserverTransport {
    var lastBody: String? = null
    val bodies = mutableListOf<String>()

    override suspend fun post(body: String): String {
        lastBody = body
        bodies += body
        val method = Regex(""""method"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
            ?: error("missing method in request")
        return responses[method] ?: error("No mock response for method $method")
    }
}

private fun suspendTest(block: suspend () -> Unit) {
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
