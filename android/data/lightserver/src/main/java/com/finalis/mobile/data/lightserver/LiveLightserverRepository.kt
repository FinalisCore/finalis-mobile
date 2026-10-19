package com.finalis.mobile.data.lightserver

import com.finalis.mobile.core.model.AddressValidationResult
import com.finalis.mobile.core.model.BalanceSnapshot
import com.finalis.mobile.core.model.BroadcastErrorCode
import com.finalis.mobile.core.model.BroadcastResult
import com.finalis.mobile.core.model.HistoryEntry
import com.finalis.mobile.core.model.HistoryPageResult
import com.finalis.mobile.core.model.NetworkIdentity
import com.finalis.mobile.core.model.TxDetail
import com.finalis.mobile.core.model.TxDirection
import com.finalis.mobile.core.model.TxInput
import com.finalis.mobile.core.model.TxOutput
import com.finalis.mobile.core.model.TxStatus
import com.finalis.mobile.core.model.WalletAddress
import com.finalis.mobile.core.model.WalletUtxo
import com.finalis.mobile.core.wallet.FinalisTxCodec
import com.finalis.mobile.core.wallet.ParsedWalletTx
import java.net.HttpURLConnection
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

class LiveLightserverRepository(
    private val transport: LightserverTransport,
    private val assetSymbol: String = "FINALIS",
    private val maxHistoryLimit: Int = 50,
) : LightserverRepository {
    private val json = DefaultJson

    override suspend fun loadStatus(): NetworkIdentity {
        val status = rpcCall<LightserverStatusDto>("get_status", emptyParams())
        requireCompatibleStatus(status)
        return NetworkIdentity(
            name = status.networkName,
            networkId = status.networkId,
            protocolVersion = status.protocolVersion,
            featureFlags = status.featureFlags,
            genesisHash = status.genesisHash,
            tipHeight = status.tip.height,
            tipHash = status.tip.transitionHash,
            serverTruth = "finalized_only",
            proofsTipOnly = true,
            finalizedHeight = status.finalizedHeight ?: status.finalizedTip?.height,
            finalizedHash = status.finalizedTransitionHash ?: status.finalizedTip?.transitionHash,
            healthyPeerCount = status.healthyPeerCount,
            establishedPeerCount = status.establishedPeerCount,
            version = status.version,
            binary = status.binary,
            binaryVersion = status.binaryVersion,
            walletApiVersion = status.walletApiVersion,
            syncMode = status.sync?.mode,
            syncSnapshotPresent = status.sync?.snapshotPresent,
            localFinalizedHeight = status.sync?.localFinalizedHeight,
            observedNetworkHeightKnown = status.sync?.observedNetworkHeightKnown,
            observedNetworkFinalizedHeight = status.sync?.observedNetworkFinalizedHeight,
            finalizedLag = status.sync?.finalizedLag,
            bootstrapSyncIncomplete = status.sync?.bootstrapSyncIncomplete,
            peerHeightDisagreement = status.sync?.peerHeightDisagreement,
            nextHeightCommitteeAvailable = status.sync?.nextHeightCommitteeAvailable,
            nextHeightProposerAvailable = status.sync?.nextHeightProposerAvailable,
            checkpointDerivationMode = status.availability?.checkpointDerivationMode,
            checkpointFallbackReason = status.availability?.checkpointFallbackReason,
            fallbackSticky = status.availability?.fallbackSticky,
            qualifiedDepth = status.availability?.adaptiveRegime?.qualifiedDepth,
            adaptiveTargetCommitteeSize = status.availability?.adaptiveRegime?.adaptiveTargetCommitteeSize,
            adaptiveMinEligible = status.availability?.adaptiveRegime?.adaptiveMinEligible,
            adaptiveMinBond = status.availability?.adaptiveRegime?.adaptiveMinBond,
            adaptiveSlack = status.availability?.adaptiveRegime?.slack,
            targetExpandStreak = status.availability?.adaptiveRegime?.targetExpandStreak,
            targetContractStreak = status.availability?.adaptiveRegime?.targetContractStreak,
            adaptiveFallbackRateBps = status.availability?.adaptiveRegime?.fallbackRateBps,
            adaptiveStickyFallbackRateBps = status.availability?.adaptiveRegime?.stickyFallbackRateBps,
            adaptiveFallbackWindowEpochs = status.availability?.adaptiveRegime?.fallbackRateWindowEpochs,
            adaptiveNearThresholdOperation = status.availability?.adaptiveRegime?.nearThresholdOperation,
            adaptiveProlongedExpandBuildup = status.availability?.adaptiveRegime?.prolongedExpandBuildup,
            adaptiveProlongedContractBuildup = status.availability?.adaptiveRegime?.prolongedContractBuildup,
            adaptiveRepeatedStickyFallback = status.availability?.adaptiveRegime?.repeatedStickyFallback,
            adaptiveDepthCollapseAfterBondIncrease = status.availability?.adaptiveRegime?.depthCollapseAfterBondIncrease,
            adaptiveTelemetryWindowEpochs = status.adaptiveTelemetrySummary?.windowEpochs,
            adaptiveTelemetrySampleCount = status.adaptiveTelemetrySummary?.sampleCount,
            adaptiveTelemetryFallbackEpochs = status.adaptiveTelemetrySummary?.fallbackEpochs,
            adaptiveTelemetryStickyFallbackEpochs = status.adaptiveTelemetrySummary?.stickyFallbackEpochs,
        )
    }

    override suspend fun validateAddress(address: String): AddressValidationResult {
        val validation = rpcCall<ValidateAddressDto>(
            "validate_address",
            validateAddressParams(address),
        )
        requireCompatibleAddressValidation(validation)
        return AddressValidationResult(
            valid = validation.valid,
            normalizedAddress = validation.normalizedAddress,
            hrp = validation.hrp,
            networkHint = validation.networkHint,
            serverNetworkHrp = validation.serverNetworkHrp,
            serverNetworkMatch = validation.serverNetworkMatch,
            addressType = validation.addressType,
            pubkeyHashHex = validation.pubkeyHashHex,
            scriptPubKeyHex = validation.scriptPubKeyHex,
            scriptHashHex = validation.scriptHashHex,
            error = validation.error,
        )
    }

    override suspend fun loadBalance(address: String): BalanceSnapshot {
        val status = rpcCall<LightserverStatusDto>("get_status", emptyParams())
        val validatedAddress = requireValidatedAddress(address)
        val utxos = rpcCall<List<UtxoDto>>(
            "get_utxos",
            scriptHashParams(validatedAddress.scriptHashHex),
        )
        val finalizedHeight = status.finalizedHeight ?: status.finalizedTip?.height ?: status.tip.height
        val confirmedUnits = utxos
            .filter { it.height > 0L && it.height <= finalizedHeight }
            .filter { it.scriptPubKeyHex.equals(validatedAddress.scriptPubKeyHex, ignoreCase = true) }
            .sumOf { it.value }
        return BalanceSnapshot(
            address = WalletAddress(validatedAddress.normalizedAddress),
            confirmedUnits = confirmedUnits,
            asset = assetSymbol,
            tipHeight = status.tip.height,
            tipHash = status.tip.transitionHash,
        )
    }

    override suspend fun loadUtxos(address: String): List<WalletUtxo> {
        val status = rpcCall<LightserverStatusDto>("get_status", emptyParams())
        val validatedAddress = requireValidatedAddress(address)
        val finalizedHeight = status.finalizedHeight ?: status.finalizedTip?.height ?: status.tip.height
        return rpcCall<List<UtxoDto>>(
            "get_utxos",
            scriptHashParams(validatedAddress.scriptHashHex),
        )
            .asSequence()
            .filter { it.height > 0L && it.height <= finalizedHeight }
            .filter { it.scriptPubKeyHex.equals(validatedAddress.scriptPubKeyHex, ignoreCase = true) }
            .map {
                WalletUtxo(
                    txid = it.txid.lowercase(),
                    vout = it.vout,
                    valueUnits = it.value,
                    height = it.height,
                    scriptPubKeyHex = it.scriptPubKeyHex.lowercase(),
                )
            }
            .sortedWith(compareByDescending<WalletUtxo> { it.valueUnits }.thenBy { it.txid }.thenBy { it.vout })
            .toList()
    }

    override suspend fun loadHistory(address: String, cursor: String?, limit: Int, fromHeight: Long?): List<HistoryEntry> {
        return loadHistoryPage(address, cursor, limit, fromHeight).items
    }

    override suspend fun loadHistoryPage(address: String, cursor: String?, limit: Int, fromHeight: Long?): HistoryPageResult {
        val validatedAddress = requireValidatedAddress(address)
        val targetScript = validatedAddress.scriptPubKeyHex.lowercase()
        val boundedLimit = limit.coerceIn(1, maxHistoryLimit)
        val parsedCursor = parseCursor(cursor)
        val page = rpcCall<HistoryPageDto>(
            "get_history_page",
            historyPageParams(
                scripthashHex = validatedAddress.scriptHashHex,
                limit = boundedLimit,
                cursor = parsedCursor?.startAfter,
                fromHeight = parsedCursor?.fromHeight ?: fromHeight,
            ),
        )
        requireCompatibleHistoryPage(page)
        val items = page.items.map { item ->
            val txPayload = rpcCall<TxResultDto>("get_tx", txidParams(item.txid))
            val tx = parseTx(txPayload.txHex)
            val computation = computeHistoryEntry(tx, targetScript)
            HistoryEntry(
                txid = item.txid.lowercase(),
                height = item.height,
                status = TxStatus.FINALIZED,
                direction = computation.direction,
                creditedValue = computation.creditedValue,
                debitedValue = computation.debitedValue,
                netValue = computation.netValue,
            )
        }
        return HistoryPageResult(
            items = items,
            nextCursor = page.nextStartAfter?.let {
                json.encodeToString(
                    HistoryPageCursorDto(
                        startAfter = it,
                        fromHeight = parsedCursor?.fromHeight ?: fromHeight,
                    ),
                )
            },
            hasMore = page.hasMore,
        )
    }

    override suspend fun loadTxDetail(txid: String): TxDetail {
        require(Hex32.matches(txid)) { "txid must be hex32" }
        val txStatus = rpcCall<TxStatusDto>("get_tx_status", txidParams(txid.lowercase()))
        requireCompatibleTxStatus(txStatus)
        require(txStatus.finalized && txStatus.height != null) { "Transaction is not finalized" }
        val txPayload = rpcCall<TxResultDto>("get_tx", txidParams(txid.lowercase()))
        val tx = parseTx(txPayload.txHex)
        return TxDetail(
            txid = txid.lowercase(),
            height = txStatus.height,
            status = TxStatus.FINALIZED,
            finalizedTransitionHash = txStatus.transitionHash?.lowercase(),
            finalizedDepth = txStatus.finalizedDepth,
            inputs = tx.inputs.map { TxInput(prevTxid = it.prevTxidHex.lowercase(), prevVout = it.prevIndex) },
            outputs = tx.outputs.mapIndexed { index, output ->
                TxOutput(
                    index = index,
                    value = output.valueUnits,
                    address = resolveOutputAddress(output.scriptPubKeyHex),
                )
            },
        )
    }

    override suspend fun findFinalizedTxDetail(txid: String): TxDetail? =
        try {
            val txStatus = rpcCall<TxStatusDto>("get_tx_status", txidParams(txid.lowercase()))
            requireCompatibleTxStatus(txStatus)
            if (!txStatus.finalized || txStatus.status == "not_found") null else loadTxDetail(txid)
        } catch (error: LightserverRpcException) {
            if (error.code == -32001) null else throw error
        }

    override suspend fun broadcastTx(txHex: String): BroadcastResult {
        val result = rpcCall<BroadcastTxResultDto>("broadcast_tx", broadcastTxParams(txHex.lowercase()))
        return BroadcastResult(
            accepted = result.accepted,
            txid = result.txid?.lowercase(),
            status = if (result.accepted) TxStatus.SUBMITTED else TxStatus.REJECTED,
            errorCode = if (result.accepted) null else normalizeBroadcastError(result.errorCode, result.error, result.errorMessage),
            error = result.errorMessage ?: result.error,
        )
    }

    private suspend inline fun <reified T> rpcCall(method: String, params: JsonObject): T {
        val request = JsonRpcRequestDto(
            id = 1,
            method = method,
            params = params,
        )
        val body = transport.post(json.encodeToString(request))
        val envelope = try {
            json.decodeFromString<JsonRpcEnvelopeDto<JsonElement>>(body)
        } catch (exc: SerializationException) {
            throw LightserverDataException("Lightserver response parsing failed", exc)
        }
        envelope.error?.let { throw LightserverRpcException(it.code, it.message) }
        val result = envelope.result ?: throw LightserverDataException("Lightserver response missing result")
        return try {
            json.decodeFromJsonElement(serializer<T>(), result)
        } catch (exc: SerializationException) {
            throw LightserverBackendIncompatibleException("Lightserver returned an incompatible $method result", exc)
        }
    }

    private fun parseTx(txHex: String): ParsedWalletTx =
        try {
            FinalisTxCodec.parseTxHex(txHex)
        } catch (error: IllegalArgumentException) {
            throw LightserverDataException(error.message ?: "Invalid tx payload", error)
        }

    private suspend fun computeHistoryEntry(tx: ParsedWalletTx, targetScriptPubKeyHex: String): HistoryComputation {
        val credited = tx.outputs.filter { it.scriptPubKeyHex.lowercase() == targetScriptPubKeyHex }.sumOf { it.valueUnits }
        var debited = 0L
        for (input in tx.inputs) {
            if (input.prevTxidHex == ZeroTxid) continue
            // Reward/settlement outputs are credited without a tx record, so get_tx reports not found
            // (-32001) for them; their value cannot be attributed here.
            val prevTxPayload = try {
                rpcCall<TxResultDto>("get_tx", txidParams(input.prevTxidHex.lowercase()))
            } catch (error: LightserverRpcException) {
                if (error.code == -32001) continue else throw error
            }
            val prevTx = parseTx(prevTxPayload.txHex)
            val prevOutput = prevTx.outputs.getOrNull(input.prevIndex)
            if (prevOutput != null && prevOutput.scriptPubKeyHex.lowercase() == targetScriptPubKeyHex) {
                debited += prevOutput.valueUnits
            }
        }
        val net = credited - debited
        val direction = when {
            credited > 0 && debited == 0L -> TxDirection.RECEIVE
            net < 0 -> TxDirection.SEND
            credited > 0 && debited > 0 -> TxDirection.SELF
            else -> TxDirection.SEND
        }
        return HistoryComputation(
            creditedValue = credited,
            debitedValue = debited,
            netValue = net,
            direction = direction,
        )
    }

    // Add these helper functions for computing scripthash locally
    private fun computeScriptHashLocally(scriptPubKeyHex: String): String {
        // Convert script_pubkey_hex to bytes
        val scriptBytes = hexStringToByteArray(scriptPubKeyHex)

        // Compute SHA256
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(scriptBytes)

        // Convert to hex string (32 bytes = 64 hex chars)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val cleanHex = hex.lowercase().trim()
        require(cleanHex.length % 2 == 0) { "Invalid hex string length: ${cleanHex.length}" }

        return ByteArray(cleanHex.length / 2).also { bytes ->
            for (i in cleanHex.indices step 2) {
                val byteValue = cleanHex.substring(i, i + 2).toInt(16)
                bytes[i / 2] = byteValue.toByte()
            }
        }
    }

    private suspend fun requireValidatedAddress(address: String): ValidatedAddressContext {
        val validation = validateAddress(address)
        if (!validation.valid) {
            throw LightserverAddressException(validation.error ?: "Address is invalid", wrongNetwork = false)
        }
        if (validation.serverNetworkMatch != true) {
            throw LightserverAddressException("Address does not match the connected network", wrongNetwork = true)
        }

        // Get scriptPubKeyHex (required)
        val scriptPubKeyHex = validation.scriptPubKeyHex ?: error("Address validation missing script_pubkey_hex")

        // If server didn't provide scripthash, compute it locally from script_pubkey_hex
        val scriptHashHex = validation.scriptHashHex ?: run {
            android.util.Log.w("LightserverRepo", "Server didn't provide scripthash_hex, computing locally")
            computeScriptHashLocally(scriptPubKeyHex)
        }

        return ValidatedAddressContext(
            normalizedAddress = validation.normalizedAddress ?: address,
            scriptPubKeyHex = scriptPubKeyHex,
            scriptHashHex = scriptHashHex,
        )
    }

    private suspend fun resolveOutputAddress(scriptPubKeyHex: String): String {
        for (hrp in listOf("sc", "tsc")) {
            val candidate = p2pkhAddressFromScript(scriptPubKeyHex, hrp) ?: continue
            val validation = runCatching { validateAddress(candidate) }.getOrNull() ?: continue
            if (validation.valid && validation.serverNetworkMatch != false) {
                return validation.normalizedAddress ?: candidate
            }
        }
        return ""
    }

    private fun parseCursor(cursor: String?): HistoryPageCursorDto? {
        if (cursor == null) return null
        return try {
            json.decodeFromString<HistoryPageCursorDto>(cursor)
        } catch (exc: SerializationException) {
            throw IllegalArgumentException("invalid cursor", exc)
        }
    }

    private fun requireCompatibleStatus(status: LightserverStatusDto) {
        if (status.finalizedHeight == null ||
            status.finalizedTransitionHash.isNullOrBlank() ||
            status.finalizedTip == null ||
            status.finalizedTip.transitionHash.isBlank() ||
            status.sync?.mode != "finalized_only"
        ) {
            throw LightserverBackendIncompatibleException("Lightserver status response is missing finalized-state fields required by the live mobile contract")
        }
        requireHex32(status.tip.transitionHash, "tip.transition_hash")
        requireHex32(status.finalizedTransitionHash, "finalized_transition_hash")
        requireHex32(status.finalizedTip.transitionHash, "finalized_tip.transition_hash")
    }

    private fun requireCompatibleAddressValidation(validation: ValidateAddressDto) {
        if (!validation.valid) return
        
        // Make scripthash_hex optional since we can compute it locally
        if (validation.normalizedAddress.isNullOrBlank() ||
            validation.scriptPubKeyHex.isNullOrBlank() ||
            // validation.scriptHashHex.isNullOrBlank() - REMOVED this check
            validation.serverNetworkHrp.isNullOrBlank() ||
            validation.serverNetworkMatch == null ||
            validation.addressType != "p2pkh"
        ) {
            throw LightserverBackendIncompatibleException(
                "Lightserver validate_address response is missing live address fields. " +
                "Present fields: normalizedAddress=${validation.normalizedAddress != null}, " +
                "scriptPubKeyHex=${validation.scriptPubKeyHex != null}, " +
                "scriptHashHex=${validation.scriptHashHex != null}, " +
                "serverNetworkHrp=${validation.serverNetworkHrp != null}, " +
                "serverNetworkMatch=${validation.serverNetworkMatch != null}, " +
                "addressType=${validation.addressType}"
            )
        }
    }

    private fun requireCompatibleHistoryPage(page: HistoryPageDto) {
        if (page.hasMore && page.nextStartAfter == null) {
            throw LightserverBackendIncompatibleException("Lightserver get_history_page response omitted next_start_after while reporting has_more=true")
        }
    }

    private fun requireCompatibleTxStatus(status: TxStatusDto) {
        if (status.finalized) {
            if (status.height == null || status.transitionHash.isNullOrBlank() || status.finalizedDepth <= 0) {
                throw LightserverBackendIncompatibleException("Lightserver get_tx_status response is missing finalized transaction fields")
            }
            requireHex32(status.transitionHash, "get_tx_status.transition_hash")
        }
    }

    private fun requireHex32(value: String?, fieldName: String) {
        if (value == null || !Hex32.matches(value)) {
            throw LightserverBackendIncompatibleException("Lightserver field $fieldName is missing or malformed")
        }
    }
}

class LightserverDataException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class LightserverBackendIncompatibleException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class LightserverAddressException(
    message: String,
    val wrongNetwork: Boolean,
) : RuntimeException(message)

class LightserverRpcException(
    val code: Int,
    override val message: String,
) : RuntimeException(message)

interface LightserverTransport {
    suspend fun post(body: String): String
}

class UrlConnectionLightserverTransport(
    private val rpcUrl: String,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 5_000,
) : LightserverTransport {
    override suspend fun post(body: String): String {
        val connection = java.net.URL(rpcUrl).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                throw LightserverDataException("Lightserver HTTP $statusCode")
            }
            responseBody
        } catch (exc: java.io.IOException) {
            throw LightserverDataException("Lightserver unavailable", exc)
        } finally {
            connection.disconnect()
        }
    }
}

private data class HistoryComputation(
    val creditedValue: Long,
    val debitedValue: Long,
    val netValue: Long,
    val direction: TxDirection,
)

private data class ValidatedAddressContext(
    val normalizedAddress: String,
    val scriptPubKeyHex: String,
    val scriptHashHex: String,
)

private fun normalizeBroadcastError(
    errorCode: String?,
    error: String?,
    errorMessage: String?,
): BroadcastErrorCode? {
    val normalizedCode = errorCode?.lowercase()
    return when (normalizedCode) {
        "tx_invalid" -> when {
            (error ?: errorMessage).orEmpty().contains("bad tx hex", ignoreCase = true) -> BroadcastErrorCode.BAD_TX_HEX
            (error ?: errorMessage).orEmpty().contains("tx parse failed", ignoreCase = true) -> BroadcastErrorCode.TX_PARSE_FAILED
            else -> BroadcastErrorCode.VALIDATION_FAILED
        }
        "tx_duplicate" -> BroadcastErrorCode.TX_DUPLICATE
        "tx_missing_or_unconfirmed_input" -> BroadcastErrorCode.TX_MISSING_OR_UNCONFIRMED_INPUT
        "tx_fee_below_min_relay" -> BroadcastErrorCode.TX_FEE_BELOW_MIN_RELAY
        "mempool_full_not_good_enough" -> BroadcastErrorCode.MEMPOOL_FULL_NOT_GOOD_ENOUGH
        "relay_unavailable" -> BroadcastErrorCode.RELAY_UNAVAILABLE
        null -> {
            if (error.isNullOrBlank() && errorMessage.isNullOrBlank()) return null
            val lowered = listOfNotNull(error, errorMessage).joinToString(" ").lowercase()
            when {
                "bad tx hex" in lowered -> BroadcastErrorCode.BAD_TX_HEX
                "tx parse failed" in lowered -> BroadcastErrorCode.TX_PARSE_FAILED
                listOf("bad script", "mismatch", "insufficient", "unsupported", "invalid", "dust").any { it in lowered } ->
                    BroadcastErrorCode.VALIDATION_FAILED
                listOf("relay", "peer", "connect", "timeout").any { it in lowered } ->
                    BroadcastErrorCode.RELAY_UNAVAILABLE
                else -> BroadcastErrorCode.UNKNOWN_REJECTION
            }
        }
        else -> BroadcastErrorCode.UNKNOWN_REJECTION
    }
}

private val DefaultJson = Json { ignoreUnknownKeys = true }
private val Hex32 = Regex("^[0-9a-fA-F]{64}$")
private const val ZeroTxid = "0000000000000000000000000000000000000000000000000000000000000000"

@Serializable
private data class JsonRpcRequestDto(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonObject,
)

@Serializable
private data class JsonRpcEnvelopeDto<T>(
    val jsonrpc: String,
    val id: Int,
    val result: T? = null,
    val error: JsonRpcErrorDto? = null,
)

@Serializable
private data class JsonRpcErrorDto(
    val code: Int,
    val message: String,
)

@Serializable
private data class TipDto(
    val height: Long,
    @SerialName("transition_hash") val transitionHash: String,
)

@Serializable
private data class LightserverStatusDto(
    @SerialName("network_name") val networkName: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("feature_flags") val featureFlags: Long,
    @SerialName("network_id") val networkId: String,
    @SerialName("genesis_hash") val genesisHash: String,
    val tip: TipDto,
    @SerialName("finalized_tip") val finalizedTip: TipDto? = null,
    @SerialName("finalized_height") val finalizedHeight: Long? = null,
    @SerialName("finalized_transition_hash") val finalizedTransitionHash: String? = null,
    @SerialName("healthy_peer_count") val healthyPeerCount: Int? = null,
    @SerialName("established_peer_count") val establishedPeerCount: Int? = null,
    val version: String? = null,
    val binary: String? = null,
    @SerialName("binary_version") val binaryVersion: String? = null,
    @SerialName("wallet_api_version") val walletApiVersion: String? = null,
    val sync: SyncStatusDto? = null,
    val availability: AvailabilityDto? = null,
    @SerialName("adaptive_telemetry_summary") val adaptiveTelemetrySummary: AdaptiveTelemetrySummaryDto? = null,
)

@Serializable
private data class SyncStatusDto(
    val mode: String? = null,
    @SerialName("snapshot_present") val snapshotPresent: Boolean? = null,
    @SerialName("local_finalized_height") val localFinalizedHeight: Long? = null,
    @SerialName("observed_network_height_known") val observedNetworkHeightKnown: Boolean? = null,
    @SerialName("observed_network_finalized_height") val observedNetworkFinalizedHeight: Long? = null,
    @SerialName("finalized_lag") val finalizedLag: Long? = null,
    @SerialName("bootstrap_sync_incomplete") val bootstrapSyncIncomplete: Boolean? = null,
    @SerialName("peer_height_disagreement") val peerHeightDisagreement: Boolean? = null,
    @SerialName("next_height_committee_available") val nextHeightCommitteeAvailable: Boolean? = null,
    @SerialName("next_height_proposer_available") val nextHeightProposerAvailable: Boolean? = null,
)

@Serializable
private data class AvailabilityDto(
    @SerialName("checkpoint_derivation_mode") val checkpointDerivationMode: String? = null,
    @SerialName("checkpoint_fallback_reason") val checkpointFallbackReason: String? = null,
    @SerialName("fallback_sticky") val fallbackSticky: Boolean? = null,
    @SerialName("adaptive_regime") val adaptiveRegime: AdaptiveRegimeDto? = null,
)

@Serializable
private data class AdaptiveRegimeDto(
    @SerialName("qualified_depth") val qualifiedDepth: Long? = null,
    @SerialName("adaptive_target_committee_size") val adaptiveTargetCommitteeSize: Long? = null,
    @SerialName("adaptive_min_eligible") val adaptiveMinEligible: Long? = null,
    @SerialName("adaptive_min_bond") val adaptiveMinBond: Long? = null,
    val slack: Long? = null,
    @SerialName("target_expand_streak") val targetExpandStreak: Long? = null,
    @SerialName("target_contract_streak") val targetContractStreak: Long? = null,
    @SerialName("fallback_rate_bps") val fallbackRateBps: Long? = null,
    @SerialName("sticky_fallback_rate_bps") val stickyFallbackRateBps: Long? = null,
    @SerialName("fallback_rate_window_epochs") val fallbackRateWindowEpochs: Long? = null,
    @SerialName("near_threshold_operation") val nearThresholdOperation: Boolean? = null,
    @SerialName("prolonged_expand_buildup") val prolongedExpandBuildup: Boolean? = null,
    @SerialName("prolonged_contract_buildup") val prolongedContractBuildup: Boolean? = null,
    @SerialName("repeated_sticky_fallback") val repeatedStickyFallback: Boolean? = null,
    @SerialName("depth_collapse_after_bond_increase") val depthCollapseAfterBondIncrease: Boolean? = null,
)

@Serializable
private data class AdaptiveTelemetrySummaryDto(
    @SerialName("window_epochs") val windowEpochs: Long? = null,
    @SerialName("sample_count") val sampleCount: Long? = null,
    @SerialName("fallback_epochs") val fallbackEpochs: Long? = null,
    @SerialName("sticky_fallback_epochs") val stickyFallbackEpochs: Long? = null,
)

@Serializable
private data class UtxoDto(
    val txid: String,
    val vout: Int,
    val value: Long,
    val height: Long,
    @SerialName("script_pubkey_hex") val scriptPubKeyHex: String,
)

@Serializable
private data class HistoryItemDto(
    val txid: String,
    val height: Long,
)

@Serializable
private data class HistoryCursorDto(
    val height: Long,
    val txid: String,
)

@Serializable
private data class HistoryPageCursorDto(
    @SerialName("start_after") val startAfter: HistoryCursorDto,
    @SerialName("from_height") val fromHeight: Long? = null,
)

@Serializable
private data class HistoryPageDto(
    val items: List<HistoryItemDto>,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("next_start_after") val nextStartAfter: HistoryCursorDto? = null,
)

@Serializable
private data class TxResultDto(
    val height: Long,
    @SerialName("tx_hex") val txHex: String,
)

@Serializable
private data class TxStatusDto(
    val txid: String,
    val status: String,
    val finalized: Boolean,
    val height: Long? = null,
    @SerialName("finalized_depth") val finalizedDepth: Long = 0,
    @SerialName("credit_safe") val creditSafe: Boolean = false,
    @SerialName("transition_hash") val transitionHash: String? = null,
)

@Serializable
private data class BroadcastTxResultDto(
    val accepted: Boolean,
    val txid: String? = null,
    val error: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
)

@Serializable
private data class ValidateAddressDto(
    val valid: Boolean,
    @SerialName("normalized_address") val normalizedAddress: String? = null,
    val hrp: String? = null,
    @SerialName("network_hint") val networkHint: String? = null,
    @SerialName("server_network_hrp") val serverNetworkHrp: String? = null,
    @SerialName("server_network_match") val serverNetworkMatch: Boolean? = null,
    @SerialName("addr_type") val addressType: String? = null,
    @SerialName("pubkey_hash_hex") val pubkeyHashHex: String? = null,
    @SerialName("script_pubkey_hex") val scriptPubKeyHex: String? = null,
    @SerialName("scripthash_hex") val scriptHashHex: String? = null,
    val error: String? = null,
)

private fun emptyParams(): JsonObject = buildJsonObject { }

private fun validateAddressParams(address: String): JsonObject = buildJsonObject {
    put("address", JsonPrimitive(address))
}

private fun scriptHashParams(scripthashHex: String): JsonObject = buildJsonObject {
    put("scripthash_hex", JsonPrimitive(scripthashHex))
}

private fun historyPageParams(
    scripthashHex: String,
    limit: Int,
    cursor: HistoryCursorDto?,
    fromHeight: Long?,
): JsonObject = buildJsonObject {
    put("scripthash_hex", JsonPrimitive(scripthashHex))
    put("limit", JsonPrimitive(limit))
    if (fromHeight != null) {
        put("from_height", JsonPrimitive(fromHeight))
    }
    if (cursor != null) {
        put(
            "start_after",
            buildJsonObject {
                put("height", JsonPrimitive(cursor.height))
                put("txid", JsonPrimitive(cursor.txid))
            },
        )
    }
}

private fun txidParams(txid: String): JsonObject = buildJsonObject {
    put("txid", JsonPrimitive(txid))
}

private fun broadcastTxParams(txHex: String): JsonObject = buildJsonObject {
    put("tx_hex", JsonPrimitive(txHex))
}

private fun p2pkhAddressFromScript(scriptPubKeyHex: String, hrp: String): String? {
    val script = runCatching { hexToBytes(scriptPubKeyHex) }.getOrNull() ?: return null
    if (script.size != 25) return null
    if (script[0] != 0x76.toByte() || script[1] != 0xa9.toByte() || script[2] != 0x14.toByte()) return null
    if (script[23] != 0x88.toByte() || script[24] != 0xac.toByte()) return null
    val payload = byteArrayOf(0x00) + script.copyOfRange(3, 23)
    val checksum = doubleSha256(hrp.toByteArray(Charsets.UTF_8) + byteArrayOf(0x00) + payload).copyOfRange(0, 4)
    return buildString {
        append(hrp)
        append('1')
        append(base32Encode(payload + checksum))
    }
}

private fun hexToBytes(value: String): ByteArray {
    require(value.length % 2 == 0) { "hex length must be even" }
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun doubleSha256(input: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(digest.digest(input))
}

private fun base32Encode(data: ByteArray): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
    val output = StringBuilder()
    var buffer = 0
    var bits = 0
    for (byte in data) {
        buffer = (buffer shl 8) or (byte.toInt() and 0xff)
        bits += 8
        while (bits >= 5) {
            output.append(alphabet[(buffer shr (bits - 5)) and 0x1f])
            bits -= 5
        }
    }
    if (bits > 0) {
        output.append(alphabet[(buffer shl (5 - bits)) and 0x1f])
    }
    return output.toString()
}
