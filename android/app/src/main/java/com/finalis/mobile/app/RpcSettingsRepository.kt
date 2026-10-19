package com.finalis.mobile.app

import android.content.Context
import android.content.SharedPreferences
import com.finalis.mobile.core.model.AddressValidationResult
import com.finalis.mobile.core.model.BalanceSnapshot
import com.finalis.mobile.core.model.BroadcastResult
import com.finalis.mobile.core.model.EndpointKind
import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.model.HistoryEntry
import com.finalis.mobile.core.model.HistoryPageResult
import com.finalis.mobile.core.model.NetworkIdentity
import com.finalis.mobile.core.model.TxDetail
import com.finalis.mobile.core.model.WalletUtxo
import com.finalis.mobile.core.wallet.WalletNetworkGuard
import com.finalis.mobile.data.lightserver.ExplorerRepository
import com.finalis.mobile.data.lightserver.LightserverAddressException
import com.finalis.mobile.data.lightserver.LightserverBackendIncompatibleException
import com.finalis.mobile.data.lightserver.LightserverDataException
import com.finalis.mobile.data.lightserver.LightserverRepository
import com.finalis.mobile.data.lightserver.LightserverRpcException
import com.finalis.mobile.data.lightserver.LiveLightserverRepository
import com.finalis.mobile.data.lightserver.MockLightserverRepository
import com.finalis.mobile.data.lightserver.UrlConnectionLightserverTransport
import com.finalis.mobile.data.lightserver.UtxoDiagnosticsSnapshot
import com.finalis.mobile.data.lightserver.normalizeEndpointUrl
import com.finalis.mobile.data.lightserver.rpcUrlFromExplorerUrl

data class PersistedRpcSettings(
    val savedEndpoints: List<RpcEndpoint>,
    val activeEndpoint: RpcEndpoint?,
    val network: FinalisNetwork = FinalisNetwork.MAINNET,
) {
    fun orderedEndpoints(): List<RpcEndpoint> {
        val active = activeEndpoint
        return if (active == null) {
            savedEndpoints
        } else {
            listOf(active) + savedEndpoints.filterNot { it.url == active.url }
        }
    }
}

interface RpcEndpointSettingsStore {
    fun loadSettings(): PersistedRpcSettings
    fun setActiveEndpoint(endpoint: RpcEndpoint)
}

class RpcSettingsRepository(
    private val preferences: SharedPreferences,
) : RpcEndpointSettingsStore {
    constructor(
        context: Context,
    ) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )
    /** The network the wallet (or onboarding, before a wallet exists) is bound to. */
    fun selectedNetwork(): FinalisNetwork =
        FinalisNetwork.fromName(preferences.getString(KEY_SELECTED_NETWORK, null)) ?: FinalisNetwork.MAINNET

    fun setSelectedNetwork(network: FinalisNetwork) {
        val committed = preferences.edit()
            .putString(KEY_SELECTED_NETWORK, network.name)
            .commit()
        check(committed) { "Failed to persist selected network" }
    }

    override fun loadSettings(): PersistedRpcSettings {
        val network = selectedNetwork()
        if (BuildConfig.USE_MOCK_LIGHTSERVER) {
            return PersistedRpcSettings(
                savedEndpoints = emptyList(),
                activeEndpoint = null,
                network = network,
            )
        }
        val defaultEndpoints = defaultRpcEndpoints(network)
        val keys = EndpointKeys.forNetwork(network)
        val savedEndpoints = (parseEndpoints(preferences.getString(keys.endpoints, null), network) + defaultEndpoints)
            .distinctBy { it.url }
        val activeUrl = preferences.getString(keys.activeEndpoint, null)
        val activeEndpoint = savedEndpoints.firstOrNull { it.url == activeUrl } ?: savedEndpoints.firstOrNull()
        val settings = PersistedRpcSettings(
            savedEndpoints = savedEndpoints,
            activeEndpoint = activeEndpoint,
            network = network,
        )
        return settings
    }

    fun loadUiState(
        inputValue: String = "",
        message: String? = null,
    ): RpcSettingsState {
        val settings = loadSettings()
        return RpcSettingsState(
            savedEndpoints = settings.savedEndpoints,
            activeEndpoint = settings.activeEndpoint,
            network = settings.network,
            inputValue = inputValue,
            message = message,
        )
    }

    fun addEndpoint(endpoint: RpcEndpoint) {
        val settings = loadSettings()
        val savedEndpoints = (settings.savedEndpoints + endpoint).distinctBy { it.url }
        saveSettings(
            savedEndpoints = savedEndpoints,
            activeEndpoint = endpoint,
        )
    }

    fun removeEndpoint(endpoint: RpcEndpoint) {
        val settings = loadSettings()
        val savedEndpoints = settings.savedEndpoints.filterNot { it.url == endpoint.url }
        val activeEndpoint = when {
            settings.activeEndpoint?.url == endpoint.url -> savedEndpoints.firstOrNull()
            savedEndpoints.any { it.url == settings.activeEndpoint?.url } -> settings.activeEndpoint
            else -> savedEndpoints.firstOrNull()
        }
        saveSettings(
            savedEndpoints = savedEndpoints,
            activeEndpoint = activeEndpoint,
        )
    }

    override fun setActiveEndpoint(endpoint: RpcEndpoint) {
        val settings = loadSettings()
        val savedEndpoints = listOf(endpoint) + settings.savedEndpoints.filterNot { it.url == endpoint.url }
        saveSettings(
            savedEndpoints = savedEndpoints,
            activeEndpoint = endpoint,
        )
    }

    private fun saveSettings(
        savedEndpoints: List<RpcEndpoint>,
        activeEndpoint: RpcEndpoint?,
    ) {
        val keys = EndpointKeys.forNetwork(selectedNetwork())
        val committed = preferences.edit()
            .putString(keys.endpoints, savedEndpoints.joinToString(separator = "\n") { it.url })
            .putString(keys.activeEndpoint, activeEndpoint?.url)
            .commit()
        check(committed) { "Failed to persist RPC settings" }
    }

    private fun parseEndpoints(raw: String?, network: FinalisNetwork): List<RpcEndpoint> =
        raw.orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { candidate ->
                runCatching { RpcEndpoint(normalizeEndpointUrl(candidate, network)) }.getOrNull()
            }
            .distinctBy { it.url }
            .toList()

    // Endpoints are kept per network so switching never probes one network's endpoints for the other.
    // Mainnet keeps the original keys so existing endpoint settings carry over.
    private data class EndpointKeys(val endpoints: String, val activeEndpoint: String) {
        companion object {
            fun forNetwork(network: FinalisNetwork): EndpointKeys =
                if (network == FinalisNetwork.MAINNET) {
                    EndpointKeys(endpoints = "saved_endpoints", activeEndpoint = "active_endpoint")
                } else {
                    EndpointKeys(endpoints = "${network.name}.saved_endpoints", activeEndpoint = "${network.name}.active_endpoint")
                }
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "rpc_settings"
        private const val KEY_SELECTED_NETWORK = "selected_network"
        internal val BUILTIN_FALLBACK_ENDPOINTS = FinalisNetwork.MAINNET.defaultEndpoints
    }
}

internal fun defaultRpcEndpoints(network: FinalisNetwork): List<RpcEndpoint> {
    val configured = if (network == FinalisNetwork.MAINNET) listOf(BuildConfig.EXPLORER_BASE_URL) else emptyList()
    return (configured + network.defaultEndpoints)
        .map { normalizeEndpointUrl(it, network) }
        .distinct()
        .map(::RpcEndpoint)
}

class RuntimeLightserverRepository(
    private val endpointSettingsStore: RpcEndpointSettingsStore,
    private val repositoryFactory: (RpcEndpoint, FinalisNetwork) -> LightserverRepository = ::lightserverRepositoryFor,
) : LightserverRepository {
    @Volatile
    private var lastUtxoSnapshot: UtxoDiagnosticsSnapshot? = null

    override suspend fun loadStatus(): NetworkIdentity {
        val settings = endpointSettingsStore.loadSettings()
        val network = settings.network
        val endpoints = settings.orderedEndpoints()
        require(endpoints.isNotEmpty()) { "No lightserver endpoints configured" }
        var lastFailureMessage: String? = null
        var firstUnavailableMessage: String? = null
        for (endpoint in endpoints) {
            val repository = repositoryFactory(endpoint, network)
            val probe = probeRpcEndpoint(endpoint, network, repositoryFactory = { _, _ -> repository })
            when {
                probe.status != null -> {
                    endpointSettingsStore.setActiveEndpoint(endpoint)
                    return probe.status
                }
                probe.error?.kind == EndpointErrorKind.UNAVAILABLE -> {
                    val unavailableMessage = "${endpoint.url}: ${probe.error.rawMessage ?: probe.error.message}"
                    if (firstUnavailableMessage == null) firstUnavailableMessage = unavailableMessage
                    lastFailureMessage = unavailableMessage
                }
                probe.error != null -> {
                    throw LightserverDataException(probeDisplayMessage(probe))
                }
                else -> {
                    lastFailureMessage = probeDisplayMessage(probe)
                }
            }
        }
        throw LightserverDataException(firstUnavailableMessage ?: lastFailureMessage ?: "No reachable Finalis lightserver endpoint")
    }

    override suspend fun validateAddress(address: String) =
        withValidatedEndpoint { repository, _, _ -> repository.validateAddress(address) }

    override suspend fun loadBalance(address: String): BalanceSnapshot =
        withValidatedEndpoint { repository, _, _ -> repository.loadBalance(address) }

    override suspend fun loadUtxos(address: String): List<WalletUtxo> =
        withValidatedEndpoint { repository, _, _ ->
            repository.loadUtxos(address).also {
                lastUtxoSnapshot = repository.lastUtxoDiagnostics()
            }
        }

    override suspend fun loadHistoryPage(address: String, cursor: String?, limit: Int, fromHeight: Long?): HistoryPageResult =
        withValidatedEndpoint { repository, _, _ -> repository.loadHistoryPage(address, cursor, limit, fromHeight) }

    override suspend fun loadHistory(address: String, cursor: String?, limit: Int, fromHeight: Long?): List<HistoryEntry> =
        withValidatedEndpoint { repository, _, _ -> repository.loadHistory(address, cursor, limit, fromHeight) }

    override suspend fun loadTxDetail(txid: String): TxDetail =
        withValidatedEndpoint { repository, _, _ -> repository.loadTxDetail(txid) }

    override suspend fun findFinalizedTxDetail(txid: String): TxDetail? =
        withValidatedEndpoint { repository, _, _ -> repository.findFinalizedTxDetail(txid) }

    override suspend fun broadcastTx(txHex: String): BroadcastResult {
        val settings = endpointSettingsStore.loadSettings()
        val network = settings.network
        val endpoints = settings.orderedEndpoints()
        require(endpoints.isNotEmpty()) { "No lightserver endpoints configured" }
        var lastResult: BroadcastResult? = null
        var lastFailureMessage: String? = null
        var firstUnavailableMessage: String? = null
        for (endpoint in endpoints) {
            val repository = repositoryFactory(endpoint, network)
            val probe = probeRpcEndpoint(endpoint, network, repositoryFactory = { _, _ -> repository })
            if (!probe.isValid) {
                if (probe.error?.kind == EndpointErrorKind.UNAVAILABLE) {
                    val unavailableMessage = "${endpoint.url}: ${probe.error.rawMessage ?: probe.error.message}"
                    if (firstUnavailableMessage == null) firstUnavailableMessage = unavailableMessage
                    lastFailureMessage = unavailableMessage
                    continue
                }
                throw LightserverDataException(probeDisplayMessage(probe))
            }
            try {
                val result = repository.broadcastTx(txHex)
                if (result.accepted) {
                    endpointSettingsStore.setActiveEndpoint(endpoint)
                    return result
                }
                lastResult = result
                if (result.errorCode != com.finalis.mobile.core.model.BroadcastErrorCode.RELAY_UNAVAILABLE) {
                    endpointSettingsStore.setActiveEndpoint(endpoint)
                    return result
                }
                lastFailureMessage = result.error ?: "Relay unavailable"
            } catch (error: Exception) {
                val failure = classifyEndpointFailure(error)
                if (failure.kind == EndpointErrorKind.UNAVAILABLE) {
                    val unavailableMessage = "${endpoint.url}: ${failure.rawMessage ?: failure.message}"
                    if (firstUnavailableMessage == null) firstUnavailableMessage = unavailableMessage
                    lastFailureMessage = unavailableMessage
                    continue
                }
                throw error
            }
        }
        return lastResult ?: throw LightserverDataException(firstUnavailableMessage ?: lastFailureMessage ?: "No reachable Finalis lightserver endpoint")
    }

    override fun lastUtxoDiagnostics(): UtxoDiagnosticsSnapshot? = lastUtxoSnapshot

    private suspend fun <T> withValidatedEndpoint(
        block: suspend (LightserverRepository, NetworkIdentity, RpcEndpoint) -> T,
    ): T {
        val settings = endpointSettingsStore.loadSettings()
        val network = settings.network
        val endpoints = settings.orderedEndpoints()
        require(endpoints.isNotEmpty()) { "No lightserver endpoints configured" }
        var lastFailureMessage: String? = null
        var firstUnavailableMessage: String? = null
        for (endpoint in endpoints) {
            val repository = repositoryFactory(endpoint, network)
            val probe = probeRpcEndpoint(endpoint, network, repositoryFactory = { _, _ -> repository })
            if (!probe.isValid) {
                if (probe.error?.kind == EndpointErrorKind.UNAVAILABLE) {
                    val unavailableMessage = "${endpoint.url}: ${probe.error.rawMessage ?: probe.error.message}"
                    if (firstUnavailableMessage == null) firstUnavailableMessage = unavailableMessage
                    lastFailureMessage = unavailableMessage
                    continue
                }
                throw LightserverDataException(probeDisplayMessage(probe))
            }
            try {
                val result = block(repository, probe.status!!, endpoint)
                endpointSettingsStore.setActiveEndpoint(endpoint)
                return result
            } catch (error: Exception) {
                val failure = classifyEndpointFailure(error)
                if (failure.kind == EndpointErrorKind.UNAVAILABLE) {
                    val unavailableMessage = "${endpoint.url}: ${failure.rawMessage ?: failure.message}"
                    if (firstUnavailableMessage == null) firstUnavailableMessage = unavailableMessage
                    lastFailureMessage = unavailableMessage
                    continue
                }
                throw error
            }
        }
        throw LightserverDataException(firstUnavailableMessage ?: lastFailureMessage ?: "No reachable Finalis lightserver endpoint")
    }
}

suspend fun probeRpcEndpoint(
    endpoint: RpcEndpoint,
    network: FinalisNetwork,
    repositoryFactory: (RpcEndpoint, FinalisNetwork) -> LightserverRepository = ::lightserverRepositoryFor,
): EndpointProbeResult {
    return try {
        val repository = repositoryFactory(endpoint, network)
        val status = repository.loadStatus()
        val mismatch = WalletNetworkGuard.detectMismatch(status, network)
        EndpointProbeResult(
            endpoint = endpoint,
            status = status,
            mismatch = mismatch,
        )
    } catch (error: Exception) {
        EndpointProbeResult(
            endpoint = endpoint,
            error = classifyEndpointFailure(error),
        )
    }
}

fun createLightserverRepository(
    rpcSettingsRepository: RpcSettingsRepository,
): LightserverRepository =
    if (BuildConfig.USE_MOCK_LIGHTSERVER) {
        MockLightserverRepository()
    } else {
        RuntimeLightserverRepository(
            endpointSettingsStore = rpcSettingsRepository,
        )
    }

fun normalizeRpcUrl(rawUrl: String, network: FinalisNetwork): String = normalizeEndpointUrl(rawUrl, network)

private fun lightserverRepositoryFor(endpoint: RpcEndpoint, network: FinalisNetwork): LightserverRepository =
    when (network.endpointKind) {
        EndpointKind.EXPLORER -> ExplorerRepository(
            explorerBaseUrl = endpoint.url,
            lightserverRpcUrl = rpcUrlFromExplorerUrl(endpoint.url),
            network = network,
        )
        EndpointKind.LIGHTSERVER_RPC -> LiveLightserverRepository(
            transport = UrlConnectionLightserverTransport(endpoint.url),
        )
    }

private fun probeDisplayMessage(probe: EndpointProbeResult): String =
    when {
        probe.mismatch != null -> "${probe.endpoint.url}: ${probe.mismatch.message}"
        probe.error != null -> "${probe.endpoint.url}: ${probe.error.rawMessage ?: probe.error.message}"
        else -> "${probe.endpoint.url}: endpoint probe failed"
    }

fun classifyEndpointFailure(error: Throwable): EndpointFailure {
    val message = error.message ?: return EndpointFailure(
        kind = EndpointErrorKind.UNKNOWN,
        message = "Lightserver request failed",
    )
    return when (error) {
        is LightserverAddressException -> EndpointFailure(
            kind = if (error.wrongNetwork) EndpointErrorKind.ADDRESS_WRONG_NETWORK else EndpointErrorKind.ADDRESS_INVALID,
            message = message,
            rawMessage = message,
        )
        is LightserverBackendIncompatibleException -> EndpointFailure(
            kind = EndpointErrorKind.INCOMPATIBLE,
            message = "The endpoint does not satisfy the live Finalis lightserver contract. $message",
            rawMessage = message,
        )
        is LightserverRpcException -> EndpointFailure(
            kind = EndpointErrorKind.RPC_ERROR,
            message = "The lightserver returned an RPC error. $message",
            rawMessage = message,
        )
        is LightserverDataException -> {
            val lowerMessage = message.lowercase()
            when {
                "upstream unavailable" in lowerMessage || "unavailable" in lowerMessage || "timeout" in lowerMessage ||
                    "http 502" in lowerMessage || "http 503" in lowerMessage || "http 504" in lowerMessage ->
                    EndpointFailure(EndpointErrorKind.UNAVAILABLE, formatEndpointErrorMessage(message), rawMessage = message)
                "http 400" in lowerMessage || "malformed address" in lowerMessage || "invalid address" in lowerMessage ->
                    EndpointFailure(EndpointErrorKind.ADDRESS_INVALID, formatEndpointErrorMessage(message), rawMessage = message)
                "http 404" in lowerMessage || "not found in finalized state" in lowerMessage ->
                    EndpointFailure(EndpointErrorKind.RPC_ERROR, formatEndpointErrorMessage(message), rawMessage = message)
                "parsing failed" in lowerMessage || "malformed data" in lowerMessage || "missing result" in lowerMessage ->
                    EndpointFailure(EndpointErrorKind.MALFORMED_DATA, formatEndpointErrorMessage(message), rawMessage = message)
                "http " in lowerMessage ->
                    EndpointFailure(EndpointErrorKind.RPC_ERROR, formatEndpointErrorMessage(message), rawMessage = message)
                else ->
                    EndpointFailure(EndpointErrorKind.INCOMPATIBLE, formatEndpointErrorMessage(message), rawMessage = message)
            }
        }
        else -> when {
            generateSequence(error) { it.cause }.any { it is java.io.IOException } ->
                EndpointFailure(EndpointErrorKind.UNAVAILABLE, formatEndpointErrorMessage(message), rawMessage = message)
            else ->
                EndpointFailure(EndpointErrorKind.UNKNOWN, message, rawMessage = message)
        }
    }
}

private fun formatEndpointErrorMessage(message: String): String =
    when {
        "upstream unavailable" in message.lowercase() || "unavailable" in message.lowercase() ||
            "http 502" in message.lowercase() || "http 503" in message.lowercase() || "http 504" in message.lowercase() ->
            "Read endpoint unavailable"
        "timeout" in message.lowercase() -> "Read endpoint request timed out"
        "parsing failed" in message.lowercase() || "malformed data" in message.lowercase() -> "Endpoint returned malformed data"
        "missing result" in message.lowercase() -> "Endpoint response was incomplete"
        "http 400" in message.lowercase() || "malformed address" in message.lowercase() || "invalid address" in message.lowercase() ->
            "Address is invalid for this network"
        "http 404" in message.lowercase() || "not found in finalized state" in message.lowercase() ->
            "Not found in finalized state"
        "http " in message.lowercase() -> "Endpoint returned an HTTP error"
        else -> message
    }
