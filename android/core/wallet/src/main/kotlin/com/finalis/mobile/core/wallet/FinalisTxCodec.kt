package com.finalis.mobile.core.wallet

import com.finalis.mobile.core.crypto.FinalisKeyDerivation
import com.finalis.mobile.core.crypto.toHex
import com.finalis.mobile.core.model.FinalisNetwork
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

data class WalletTxInput(
    val prevTxidHex: String,
    val prevIndex: Int,
    val scriptSigHex: String,
    val sequence: Int = 0xffffffff.toInt(),
)

data class WalletTxOutput(
    val valueUnits: Long,
    val scriptPubKeyHex: String,
)

data class WalletTx(
    val version: Int = 1,
    val inputs: List<WalletTxInput>,
    val outputs: List<WalletTxOutput>,
    val lockTime: Int = 0,
)

data class ParsedWalletTx(
    val version: Int,
    val inputs: List<WalletTxInput>,
    val outputs: List<WalletTxOutput>,
    val lockTime: Int,
)

object FinalisTxCodec {
    /** Core `Tx::serialize_without_hashcash`: the txid and sighash preimage. */
    fun serializeTxWithoutHashcash(tx: WalletTx): ByteArray {
        val writer = TxWriter()
        writer.u32le(tx.version)
        writer.varint(tx.inputs.size.toLong())
        tx.inputs.forEach { input ->
            writer.bytes(normalizeHex32(input.prevTxidHex, "prevTxidHex"))
            writer.u32le(input.prevIndex)
            writer.varbytes(input.scriptSigHex.hexToBytes())
            writer.u32le(input.sequence)
        }
        writer.varint(tx.outputs.size.toLong())
        tx.outputs.forEach { output ->
            writer.u64le(output.valueUnits)
            writer.varbytes(output.scriptPubKeyHex.hexToBytes())
        }
        writer.u32le(tx.lockTime)
        return writer.finish()
    }

    /** Core `Tx::serialize` for a tx without a hashcash stamp (trailing marker byte 0). */
    fun serializeTx(tx: WalletTx): ByteArray = serializeTxWithoutHashcash(tx) + byteArrayOf(0)

    fun txidHex(tx: WalletTx): String = sha256d(serializeTxWithoutHashcash(tx)).toHex()

    /** Core `chain_domain_from_ids`: sha256d("SC-CHAIN-DOMAIN-V1" || network_id[16] || genesis_hash[32]). */
    fun chainDomain(networkIdHex: String, genesisHashHex: String): ByteArray {
        val networkId = networkIdHex.hexToBytes()
        require(networkId.size == 16) { "networkId must be 16 bytes hex" }
        val writer = TxWriter()
        writer.bytes("SC-CHAIN-DOMAIN-V1".toByteArray(Charsets.UTF_8))
        writer.bytes(networkId)
        writer.bytes(normalizeHex32(genesisHashHex, "genesisHash"))
        return sha256d(writer.finish())
    }

    fun chainDomain(network: FinalisNetwork): ByteArray {
        val genesisHash = network.genesisHash
            ?: throw IllegalStateException(
                "Finalis ${network.name} genesis hash is not published yet; transactions cannot be signed for it.",
            )
        return chainDomain(network.networkId, genesisHash)
    }

    /** Core `spent_outputs_hash`: sha256d("SC-SPENT-V1" || varint(n) || n x (u64le(value) || varbytes(script_pubkey))). */
    fun spentOutputsHash(spent: List<WalletTxOutput>): ByteArray {
        val writer = TxWriter()
        writer.bytes("SC-SPENT-V1".toByteArray(Charsets.UTF_8))
        writer.varint(spent.size.toLong())
        spent.forEach { output ->
            writer.u64le(output.valueUnits)
            writer.varbytes(output.scriptPubKeyHex.hexToBytes())
        }
        return sha256d(writer.finish())
    }

    /**
     * Core `signing_message_for_input`:
     * sha256d("SC-SIG-V4" || chain_domain[32] || u32le(input_index) || sha256d(tx without hashcash, script_sigs cleared)
     *   || spentOutputsHash(spent)).
     * `spent` is the output each input spends, in input order, as the chain holds it: a wrong value or
     * script for any of them makes every signature of the transaction invalid.
     */
    fun signingMessageForInput(
        tx: WalletTx,
        inputIndex: Int,
        chainDomain: ByteArray,
        spent: List<WalletTxOutput>,
    ): ByteArray {
        require(inputIndex in tx.inputs.indices) { "Input index out of range" }
        require(chainDomain.size == 32) { "Chain domain must be 32 bytes" }
        require(spent.size == tx.inputs.size) { "Spent outputs must match the inputs" }
        val txCopy = tx.copy(inputs = tx.inputs.map { it.copy(scriptSigHex = "") })
        val txHash = sha256d(serializeTxWithoutHashcash(txCopy))
        val writer = TxWriter()
        writer.bytes("SC-SIG-V4".toByteArray(Charsets.UTF_8))
        writer.bytes(chainDomain)
        writer.u32le(inputIndex)
        writer.bytes(txHash)
        writer.bytes(spentOutputsHash(spent))
        return sha256d(writer.finish())
    }

    fun signInputP2PKH(
        tx: WalletTx,
        inputIndex: Int,
        chainDomain: ByteArray,
        spent: List<WalletTxOutput>,
        privateKeyHex: String,
        publicKeyHex: String,
    ): WalletTx {
        val signature = FinalisKeyDerivation.signEd25519(
            signingMessageForInput(tx, inputIndex, chainDomain, spent),
            privateKeyHex,
        )
        val publicKey = publicKeyHex.hexToBytes()
        require(publicKey.size == 32) { "Public key must be 32 bytes hex" }
        val scriptSig = TxWriter()
            .u8(0x40)
            .bytes(signature)
            .u8(0x20)
            .bytes(publicKey)
            .finish()

        val updatedInputs = tx.inputs.toMutableList()
        updatedInputs[inputIndex] = updatedInputs[inputIndex].copy(scriptSigHex = scriptSig.toHex())
        return tx.copy(inputs = updatedInputs)
    }

    fun signAllInputsP2PKH(
        tx: WalletTx,
        chainDomain: ByteArray,
        spent: List<WalletTxOutput>,
        privateKeyHex: String,
        publicKeyHex: String,
    ): WalletTx {
        var signed = tx
        for (index in tx.inputs.indices) {
            signed = signInputP2PKH(signed, index, chainDomain, spent, privateKeyHex, publicKeyHex)
        }
        return signed
    }

    fun parseTxHex(txHex: String): ParsedWalletTx {
        val reader = TxReader(txHex.hexToBytes())
        val version = reader.u32le()
        val inputCount = reader.varint()
        val inputs = buildList {
            repeat(inputCount.toInt()) {
                add(
                    WalletTxInput(
                        prevTxidHex = reader.bytes(32).toHex(),
                        prevIndex = reader.u32le(),
                        scriptSigHex = reader.varbytes().toHex(),
                        sequence = reader.u32le(),
                    )
                )
            }
        }
        val outputCount = reader.varint()
        val outputs = buildList {
            repeat(outputCount.toInt()) { index ->
                add(
                    WalletTxOutput(
                        valueUnits = reader.u64le(),
                        scriptPubKeyHex = reader.varbytes().toHex(),
                    )
                )
            }
        }
        val lockTime = reader.u32le()

        if (!reader.isAtEnd()) {
            when (val marker = reader.u8()) {
                0 -> {}
                1 -> {
                    reader.u32le()
                    reader.u64le()
                    reader.u32le()
                    reader.u64le()
                }

                else -> throw IllegalArgumentException("Invalid hashcash marker: $marker")
            }
        }

        require(reader.isAtEnd()) { "Trailing tx bytes" }
        return ParsedWalletTx(
            version = version,
            inputs = inputs,
            outputs = outputs,
            lockTime = lockTime,
        )
    }

    private fun normalizeHex32(hex: String, field: String): ByteArray {
        val bytes = hex.hexToBytes()
        require(bytes.size == 32) { "$field must be 32 bytes hex" }
        return bytes
    }

    private fun sha256d(input: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(digest.digest(input))
    }
}

private class TxReader(
    private val bytes: ByteArray,
) {
    private var offset: Int = 0

    fun isAtEnd(): Boolean = offset == bytes.size

    fun u8(): Int = bytes(1)[0].toInt() and 0xff

    fun u32le(): Int {
        val chunk = bytes(4)
        return (chunk[0].toInt() and 0xff) or
            ((chunk[1].toInt() and 0xff) shl 8) or
            ((chunk[2].toInt() and 0xff) shl 16) or
            ((chunk[3].toInt() and 0xff) shl 24)
    }

    fun u64le(): Long {
        val chunk = bytes(8)
        var value = 0L
        repeat(8) { shift ->
            value = value or ((chunk[shift].toLong() and 0xffL) shl (8 * shift))
        }
        return value
    }

    // Core codec varint: minimal unsigned LEB128 (src/codec/varint.cpp).
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift <= 63) {
            val byte = u8()
            val chunk = (byte and 0x7f).toLong()
            require(!(shift == 63 && chunk > 1)) { "varint overflow" }
            result = result or (chunk shl shift)
            if (byte and 0x80 == 0) {
                require(byte != 0 || shift == 0) { "non-minimal varint" }
                return result
            }
            shift += 7
        }
        throw IllegalArgumentException("varint overflow")
    }

    fun varbytes(): ByteArray = bytes(varint().toInt())

    fun bytes(length: Int): ByteArray {
        require(offset + length <= bytes.size) { "Unexpected end of tx" }
        val chunk = bytes.copyOfRange(offset, offset + length)
        offset += length
        return chunk
    }
}

private class TxWriter {
    private val out = ByteArrayOutputStream()

    fun u8(value: Int): TxWriter {
        out.write(value and 0xff)
        return this
    }

    fun u32le(value: Int): TxWriter {
        repeat(4) { shift -> out.write((value ushr (8 * shift)) and 0xff) }
        return this
    }

    fun u64le(value: Long): TxWriter {
        require(value >= 0) { "value must be non-negative" }
        repeat(8) { shift -> out.write(((value ushr (8 * shift)) and 0xff).toInt()) }
        return this
    }

    // Core codec varint: unsigned LEB128 (src/codec/varint.cpp).
    fun varint(value: Long): TxWriter {
        require(value >= 0) { "varint must be non-negative" }
        var remaining = value
        do {
            var byte = (remaining and 0x7f).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) byte = byte or 0x80
            out.write(byte)
        } while (remaining != 0L)
        return this
    }

    fun bytes(bytes: ByteArray): TxWriter {
        out.write(bytes)
        return this
    }

    fun varbytes(bytes: ByteArray): TxWriter {
        varint(bytes.size.toLong())
        bytes(bytes)
        return this
    }

    fun finish(): ByteArray = out.toByteArray()
}

private fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length" }
    val out = ByteArray(length / 2)
    for (index in out.indices) {
        val offset = index * 2
        out[index] = substring(offset, offset + 2).toInt(16).toByte()
    }
    return out
}
