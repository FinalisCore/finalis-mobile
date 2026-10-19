package com.finalis.mobile.core.model

data class WalletProfile(
    val address: WalletAddress,
    val publicKeyHex: String = "",
    val label: String = "Primary wallet",
    val network: FinalisNetwork = FinalisNetwork.MAINNET,
)
