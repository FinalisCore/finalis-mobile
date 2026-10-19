package com.finalis.mobile.core.wallet

import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.model.ImportedWalletRecord
import com.finalis.mobile.core.model.WalletAddress
import com.finalis.mobile.core.model.WalletProfile

object WalletSessionManagerTestFixtures {
    fun wallet(
        address: String = "sc1adrqzotqb6xl76opjawtlcn5ds6msa6sqaw7dj7p",
        privateKeyHex: String = "00".repeat(32),
        publicKeyHex: String = "11".repeat(32),
        network: FinalisNetwork = FinalisNetwork.fromAddress(address) ?: FinalisNetwork.MAINNET,
    ): ImportedWalletRecord = ImportedWalletRecord(
        privateKeyHex = privateKeyHex,
        walletProfile = WalletProfile(
            address = WalletAddress(address),
            publicKeyHex = publicKeyHex,
            label = "Finalis Wallet",
            network = network,
        ),
        importedAtEpochMillis = 1L,
    )
}
