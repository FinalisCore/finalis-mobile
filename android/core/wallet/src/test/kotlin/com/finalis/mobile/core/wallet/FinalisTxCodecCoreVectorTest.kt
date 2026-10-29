package com.finalis.mobile.core.wallet

import com.finalis.mobile.core.crypto.FinalisAddressCodec
import com.finalis.mobile.core.crypto.FinalisKeyDerivation
import com.finalis.mobile.core.crypto.toHex
import com.finalis.mobile.core.model.FinalisNetwork
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Byte-for-byte check against `shared/test-vectors/tx/sighash-v4-testnet.json`, which is generated
 * by finalis-core itself (`shared/tooling/core-vectors/sighash_vector.cpp`).
 */
class FinalisTxCodecCoreVectorTest {
    private val vector = loadVector("tx/sighash-v4-testnet.json")

    private val unsignedTx = WalletTx(
        version = 1,
        inputs = listOf(
            WalletTxInput(prevTxidHex = hexRange(0x10), prevIndex = 0, scriptSigHex = ""),
            WalletTxInput(prevTxidHex = hexRange(0x40), prevIndex = 3, scriptSigHex = ""),
        ),
        outputs = listOf(
            WalletTxOutput(150_000L, FinalisAddressCodec.scriptPubKeyHexFromAddress(vector.getValue("recipient_address"))),
            WalletTxOutput(48_000L, FinalisAddressCodec.scriptPubKeyHexFromAddress(vector.getValue("sender_address"))),
        ),
        lockTime = 0,
    )

    private val spent = listOf(
        WalletTxOutput(vector.getValue("spent_0_value").toLong(), vector.getValue("spent_0_script_pubkey_hex")),
        WalletTxOutput(vector.getValue("spent_1_value").toLong(), vector.getValue("spent_1_script_pubkey_hex")),
    )

    @Test
    fun `vector identity matches FinalisNetwork TESTNET`() {
        assertEquals(FinalisNetwork.TESTNET.name, vector.getValue("network_name"))
        assertEquals(FinalisNetwork.TESTNET.networkId, vector.getValue("network_id_hex"))
        assertEquals(FinalisNetwork.TESTNET.genesisHash, vector.getValue("genesis_hash_hex"))
    }

    @Test
    fun `chain domain matches core`() {
        assertEquals(vector.getValue("chain_domain_hex"), FinalisTxCodec.chainDomain(FinalisNetwork.TESTNET).toHex())
    }

    @Test
    fun `key and tsc address derivation match core`() {
        val keyMaterial = FinalisKeyDerivation.deriveKeyMaterial(vector.getValue("private_key_hex"))
        assertEquals(vector.getValue("public_key_hex"), keyMaterial.publicKeyHex)
        assertEquals(
            vector.getValue("sender_address"),
            FinalisAddressCodec.deriveAddressFromPublicKey(keyMaterial.publicKeyHex, FinalisNetwork.TESTNET.hrp),
        )
    }

    @Test
    fun `unsigned serialization matches core serialize_without_hashcash`() {
        assertEquals(vector.getValue("unsigned_tx_hex"), FinalisTxCodec.serializeTxWithoutHashcash(unsignedTx).toHex())
    }

    @Test
    fun `spent outputs hash matches core spent_outputs_hash`() {
        assertEquals(vector.getValue("spent_outputs_hash_hex"), FinalisTxCodec.spentOutputsHash(spent).toHex())
    }

    @Test
    fun `sighash per input matches core signing_message_for_input`() {
        val domain = FinalisTxCodec.chainDomain(FinalisNetwork.TESTNET)
        assertEquals(vector.getValue("sighash_input_0_hex"), FinalisTxCodec.signingMessageForInput(unsignedTx, 0, domain, spent).toHex())
        assertEquals(vector.getValue("sighash_input_1_hex"), FinalisTxCodec.signingMessageForInput(unsignedTx, 1, domain, spent).toHex())
    }

    @Test
    fun `sighash covers the spent value and script`() {
        val domain = FinalisTxCodec.chainDomain(FinalisNetwork.TESTNET)
        val expected = vector.getValue("sighash_input_0_hex")
        val lowerValue = listOf(spent[0].copy(valueUnits = spent[0].valueUnits - 1), spent[1])
        val otherScript = listOf(spent[0], spent[1].copy(scriptPubKeyHex = vector.getValue("spent_0_script_pubkey_hex").replaceRange(6, 8, "00")))
        assertFailsWith<AssertionError> {
            assertEquals(expected, FinalisTxCodec.signingMessageForInput(unsignedTx, 0, domain, lowerValue).toHex())
        }
        assertFailsWith<AssertionError> {
            assertEquals(expected, FinalisTxCodec.signingMessageForInput(unsignedTx, 0, domain, otherScript).toHex())
        }
        assertFailsWith<IllegalArgumentException> {
            FinalisTxCodec.signingMessageForInput(unsignedTx, 0, domain, spent.take(1))
        }
    }

    @Test
    fun `signed tx and txid match core`() {
        val signed = FinalisTxCodec.signAllInputsP2PKH(
            tx = unsignedTx,
            chainDomain = FinalisTxCodec.chainDomain(FinalisNetwork.TESTNET),
            spent = spent,
            privateKeyHex = vector.getValue("private_key_hex"),
            publicKeyHex = vector.getValue("public_key_hex"),
        )
        assertEquals(vector.getValue("signed_tx_hex"), FinalisTxCodec.serializeTx(signed).toHex())
        assertEquals(vector.getValue("signed_txid_hex"), FinalisTxCodec.txidHex(signed))
    }

    @Test
    fun `send service output matches core vector`() {
        val wallet = WalletSessionManagerTestFixtures.wallet(
            address = vector.getValue("sender_address"),
            privateKeyHex = vector.getValue("private_key_hex"),
            publicKeyHex = vector.getValue("public_key_hex"),
            network = FinalisNetwork.TESTNET,
        )
        val senderScript = FinalisAddressCodec.scriptPubKeyHexFromAddress(vector.getValue("sender_address"))
        val built = WalletSendService().buildAndSignTransaction(
            wallet = wallet,
            spendableUtxos = listOf(
                com.finalis.mobile.core.model.WalletUtxo(hexRange(0x40), 3, 79_000L, 10L, senderScript),
                com.finalis.mobile.core.model.WalletUtxo(hexRange(0x10), 0, 120_000L, 10L, senderScript),
            ),
            recipientAddress = vector.getValue("recipient_address"),
            amountUnits = 150_000L,
            requestedFeeUnits = 1_000L,
        )
        assertEquals(vector.getValue("signed_tx_hex"), built.txHex)
        assertEquals(vector.getValue("signed_txid_hex"), built.txid)
    }

    @Test
    fun `multi-byte ULEB128 varints round trip core encoding`() {
        val hex = vector.getValue("long_script_tx_hex")
        val parsed = FinalisTxCodec.parseTxHex(hex)
        assertEquals(200, parsed.inputs.single().scriptSigHex.length / 2)
        assertEquals(300, parsed.outputs.single().scriptPubKeyHex.length / 2)

        val rebuilt = WalletTx(parsed.version, parsed.inputs, parsed.outputs, parsed.lockTime)
        assertEquals(hex, FinalisTxCodec.serializeTx(rebuilt).toHex())
    }

    @Test
    fun `parses signed core tx`() {
        val parsed = FinalisTxCodec.parseTxHex(vector.getValue("signed_tx_hex"))
        assertEquals(2, parsed.inputs.size)
        assertEquals(listOf(150_000L, 48_000L), parsed.outputs.map { it.valueUnits })
    }

    @Test
    fun `refuses to sign for a network without a published genesis`() {
        assertFailsWith<IllegalStateException> { FinalisTxCodec.chainDomain(FinalisNetwork.MAINNET) }
    }

    @Test
    fun `sighash depends on chain domain`() {
        val testnet = FinalisTxCodec.chainDomain(FinalisNetwork.TESTNET)
        val other = FinalisTxCodec.chainDomain(FinalisNetwork.MAINNET.networkId, FinalisNetwork.TESTNET.genesisHash!!)
        assertEquals(32, testnet.size)
        assertFailsWith<AssertionError> {
            assertContentEquals(
                FinalisTxCodec.signingMessageForInput(unsignedTx, 0, testnet, spent),
                FinalisTxCodec.signingMessageForInput(unsignedTx, 0, other, spent),
            )
        }
    }

    private fun hexRange(start: Int): String = (0 until 32).joinToString("") { "%02x".format(start + it) }

    private fun loadVector(relativePath: String): Map<String, String> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "shared/test-vectors/$relativePath")
            if (candidate.isFile) {
                return Regex("\"([a-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"")
                    .findAll(candidate.readText())
                    .associate { it.groupValues[1] to it.groupValues[2] }
            }
            dir = dir.parentFile
        }
        error("shared/test-vectors/$relativePath not found above ${System.getProperty("user.dir")}")
    }
}
