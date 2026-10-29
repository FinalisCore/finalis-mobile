package com.finalis.mobile.core.wallet

import com.finalis.mobile.core.crypto.FinalisAddressCodec
import com.finalis.mobile.core.crypto.toHex
import com.finalis.mobile.core.model.ImportedWalletRecord
import com.finalis.mobile.core.model.WalletOutPoint
import com.finalis.mobile.core.model.WalletUtxo

data class BuiltTransaction(
    val txHex: String,
    val txid: String,
    val inputs: List<WalletUtxo>,
    val outputs: List<WalletTxOutput>,
    val requestedFeeUnits: Long,
    val appliedFeeUnits: Long,
    val changeUnits: Long,
    val amountUnits: Long,
    val recipientAddress: String,
)

class WalletSendService(
    private val dustThresholdUnits: Long = DEFAULT_DUST_THRESHOLD_UNITS,
    private val defaultFeeUnits: Long = DEFAULT_FEE_UNITS,
) {
    fun buildAndSignTransaction(
        wallet: ImportedWalletRecord,
        spendableUtxos: List<WalletUtxo>,
        recipientAddress: String,
        amountUnits: Long,
        requestedFeeUnits: Long = defaultFeeUnits,
        reservedOutPoints: Set<WalletOutPoint> = emptySet(),
    ): BuiltTransaction {
        require(amountUnits > 0) { "Amount must be positive" }
        require(requestedFeeUnits >= 0) { "Fee must be non-negative" }

        val target = amountUnits + requestedFeeUnits
        val selected = CoinSelection.selectCoins(
            filterSpendableUtxos(spendableUtxos, reservedOutPoints),
            target,
        )
        val totalInput = selected.sumOf { it.valueUnits }
        val rawChange = totalInput - target
        val network = wallet.walletProfile.network
        val recipientHrp = FinalisAddressCodec.decodeAddress(recipientAddress).hrp
        require(recipientHrp == network.hrp) {
            "Recipient address is not a Finalis ${network.name} address (expected ${network.hrp}1…)"
        }
        val chainDomain = FinalisTxCodec.chainDomain(network)
        val recipientScript = FinalisAddressCodec.scriptPubKeyHexFromAddress(recipientAddress)
        val changeScript = FinalisAddressCodec.scriptPubKeyHexFromAddress(wallet.walletProfile.address.value)

        val outputs = mutableListOf(
            WalletTxOutput(
                valueUnits = amountUnits,
                scriptPubKeyHex = recipientScript,
            )
        )

        val changeUnits = if (rawChange > dustThresholdUnits) rawChange else 0L
        if (changeUnits > 0) {
            outputs += WalletTxOutput(
                valueUnits = changeUnits,
                scriptPubKeyHex = changeScript,
            )
        }

        val unsignedTx = WalletTx(
            version = 1,
            inputs = selected.map { utxo ->
                WalletTxInput(
                    prevTxidHex = utxo.txid,
                    prevIndex = utxo.vout,
                    scriptSigHex = "",
                    sequence = 0xffffffff.toInt(),
                )
            },
            outputs = outputs,
            lockTime = 0,
        )
        val signedTx = FinalisTxCodec.signAllInputsP2PKH(
            tx = unsignedTx,
            chainDomain = chainDomain,
            spent = selected.map { WalletTxOutput(valueUnits = it.valueUnits, scriptPubKeyHex = it.scriptPubKeyHex) },
            privateKeyHex = wallet.privateKeyHex,
            publicKeyHex = wallet.walletProfile.publicKeyHex,
        )

        return BuiltTransaction(
            txHex = FinalisTxCodec.serializeTx(signedTx).toHex(),
            txid = FinalisTxCodec.txidHex(signedTx),
            inputs = selected,
            outputs = outputs,
            requestedFeeUnits = requestedFeeUnits,
            appliedFeeUnits = requestedFeeUnits + (rawChange - changeUnits),
            changeUnits = changeUnits,
            amountUnits = amountUnits,
            recipientAddress = recipientAddress,
        )
    }

    companion object {
        const val DEFAULT_FEE_UNITS: Long = 1_000L
        const val DEFAULT_DUST_THRESHOLD_UNITS: Long = 546L
    }
}

fun filterSpendableUtxos(
    utxos: List<WalletUtxo>,
    reservedOutPoints: Set<WalletOutPoint>,
): List<WalletUtxo> =
    utxos.filterNot { utxo ->
        reservedOutPoints.contains(
            WalletOutPoint(
                txid = utxo.txid.lowercase(),
                vout = utxo.vout,
            ),
        )
    }
