package com.finalis.mobile.core.model

/** How the wallet reads a network's endpoints. */
enum class EndpointKind {
    /** Finalis Explorer REST API for reads; lightserver JSON-RPC (derived URL) for broadcast. */
    EXPLORER,

    /** Lightserver JSON-RPC (`.../rpc`) for reads and broadcast. */
    LIGHTSERVER_RPC,
}

/**
 * Chain identity the wallet binds to. Values mirror finalis-core `src/common/network.cpp`
 * and `<network>/genesis.json`.
 */
data class FinalisNetwork(
    val name: String,
    val displayName: String,
    val hrp: String,
    val networkId: String,
    /** Null until the network's genesis is published; sends are refused while it is unknown. */
    val genesisHash: String?,
    val magic: Long,
    val endpointKind: EndpointKind,
    val defaultEndpoints: List<String>,
) {
    val isTestnet: Boolean get() = this == TESTNET

    companion object {
        // Core embeds no mainnet genesis yet (MAINNET_GENESIS_BIN_LEN == 0 until launch and
        // mainnet/genesis.bin is unpublished), so the hash is deliberately left unset.
        val MAINNET = FinalisNetwork(
            name = "mainnet",
            displayName = "Mainnet",
            hrp = "sc",
            networkId = "fe561911730912cced1e83bc273fab13",
            genesisHash = null,
            magic = 0x499602D2,
            endpointKind = EndpointKind.EXPLORER,
            defaultEndpoints = listOf(
                "http://85.217.171.168:18080",
                "http://64.23.244.126:18080",
            ),
        )

        val TESTNET = FinalisNetwork(
            name = "testnet",
            displayName = "Testnet",
            hrp = "tsc",
            networkId = "a6ffe3b32f81d1ad7df761693dc87dd8",
            genesisHash = "4faa7e1b8e79509c767f7f51f711eba7c75e9e9e4d9ea105fa020dd15cdd9d0c",
            magic = 1414742833,
            endpointKind = EndpointKind.LIGHTSERVER_RPC,
            defaultEndpoints = listOf(
                "http://130.61.126.39:19544/rpc",
            ),
        )

        val ALL: List<FinalisNetwork> = listOf(MAINNET, TESTNET)

        fun fromName(name: String?): FinalisNetwork? = ALL.firstOrNull { it.name == name }

        fun fromAddress(address: String): FinalisNetwork? {
            val normalized = address.trim().lowercase()
            return ALL.firstOrNull { normalized.startsWith("${it.hrp}1") }
        }
    }
}
