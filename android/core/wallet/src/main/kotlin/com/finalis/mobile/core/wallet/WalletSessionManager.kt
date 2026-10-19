package com.finalis.mobile.core.wallet

import com.finalis.mobile.core.crypto.FinalisAddressCodec
import com.finalis.mobile.core.crypto.FinalisKeyDerivation
import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.model.ImportedWalletRecord
import com.finalis.mobile.core.model.WalletAddress
import com.finalis.mobile.core.model.WalletProfile
import com.finalis.mobile.core.storage.WalletStore

class WalletSessionManager(
    private val walletStore: WalletStore,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    fun loadCurrentWallet(): ImportedWalletRecord? = walletStore.load()

    fun importAndPersist(
        privateKeyHex: String,
        network: FinalisNetwork,
        label: String = "Primary wallet",
    ): ImportedWalletRecord {
        val keyMaterial = FinalisKeyDerivation.deriveKeyMaterial(privateKeyHex)
        val record = ImportedWalletRecord(
            privateKeyHex = keyMaterial.privateKeyHex,
            walletProfile = profileFor(keyMaterial.publicKeyHex, network, label),
            importedAtEpochMillis = nowEpochMillis(),
        )
        walletStore.save(record)
        return record
    }

    /** Re-binds the same key to [network]: the address is re-derived with that network's HRP. */
    fun rebindNetwork(record: ImportedWalletRecord, network: FinalisNetwork): ImportedWalletRecord {
        val keyMaterial = FinalisKeyDerivation.deriveKeyMaterial(record.privateKeyHex)
        val rebound = record.copy(
            walletProfile = profileFor(keyMaterial.publicKeyHex, network, record.walletProfile.label),
        )
        walletStore.save(rebound)
        return rebound
    }

    private fun profileFor(publicKeyHex: String, network: FinalisNetwork, label: String): WalletProfile =
        WalletProfile(
            address = WalletAddress(FinalisAddressCodec.deriveAddressFromPublicKey(publicKeyHex, network.hrp)),
            publicKeyHex = publicKeyHex,
            label = label,
            network = network,
        )

    fun clearWallet() {
        walletStore.clear()
    }

    fun exportPrivateKeyHex(record: ImportedWalletRecord): String =
        FinalisKeyDerivation.normalizePrivateKeyHex(record.privateKeyHex)
}
