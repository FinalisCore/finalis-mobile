package com.finalis.mobile.core.wallet

import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.model.NetworkIdentity

data class NetworkMismatch(
    val code: String,
    val message: String,
)

object WalletNetworkGuard {
    fun detectMismatch(status: NetworkIdentity, network: FinalisNetwork): NetworkMismatch? {
        if (status.name != network.name) {
            return NetworkMismatch(
                code = "invalid_network_name",
                message = "This wallet is on Finalis ${network.name}. The endpoint reported ${status.name}.",
            )
        }

        val networkId = status.networkId
        if (networkId != null &&
            networkId.lowercase() != network.networkId
        ) {
            return NetworkMismatch(
                code = "invalid_network_id",
                message = "This endpoint is not Finalis ${network.name} (network id ${networkId.lowercase()}).",
            )
        }

        // An unpublished genesis (mainnet before launch) cannot be pinned; name and network id still are.
        val expectedGenesisHash = network.genesisHash
        val genesisHash = status.genesisHash
        if (expectedGenesisHash != null &&
            genesisHash != null &&
            genesisHash.lowercase() != expectedGenesisHash
        ) {
            return NetworkMismatch(
                code = "invalid_genesis_hash",
                message = "This endpoint is not Finalis ${network.name} (genesis ${genesisHash.lowercase()}).",
            )
        }

        return null
    }
}
