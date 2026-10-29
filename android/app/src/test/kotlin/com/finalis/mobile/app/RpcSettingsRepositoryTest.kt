package com.finalis.mobile.app

import android.content.SharedPreferences
import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.data.lightserver.LightserverDataException
import com.finalis.mobile.data.lightserver.LightserverRpcException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RpcSettingsRepositoryTest {


    @Test
    fun `load settings appends builtin fallback endpoints`() {
        val preferences = FakeSharedPreferences()
        val repository = RpcSettingsRepository(preferences)
        val settings = repository.loadSettings()
        val expectedEndpoints = (listOf(BuildConfig.EXPLORER_BASE_URL) +
            RpcSettingsRepository.BUILTIN_FALLBACK_ENDPOINTS)
            .map { normalizeRpcUrl(it, FinalisNetwork.MAINNET) }
            .distinct()

        assertEquals(
            expectedEndpoints.map(::RpcEndpoint),
            settings.savedEndpoints,
        )
        assertEquals(RpcEndpoint(normalizeRpcUrl(BuildConfig.EXPLORER_BASE_URL, FinalisNetwork.MAINNET)), settings.activeEndpoint)
        assertEquals(FinalisNetwork.MAINNET, settings.network)
    }

    @Test
    fun `testnet selection uses the testnet lightserver rpc default`() {
        val repository = RpcSettingsRepository(FakeSharedPreferences())
        repository.setSelectedNetwork(FinalisNetwork.TESTNET)

        val settings = repository.loadSettings()

        assertEquals(FinalisNetwork.TESTNET, settings.network)
        assertEquals(
            listOf(RpcEndpoint("https://159.200.236.114/rpc"), RpcEndpoint("http://130.61.126.39:19544/rpc")),
            settings.savedEndpoints,
        )
        assertEquals(RpcEndpoint("https://159.200.236.114/rpc"), settings.activeEndpoint)
    }

    @Test
    fun `endpoints are stored per network`() {
        val preferences = FakeSharedPreferences()
        val repository = RpcSettingsRepository(preferences)
        repository.addEndpoint(RpcEndpoint("https://mainnet-explorer.example"))

        repository.setSelectedNetwork(FinalisNetwork.TESTNET)
        repository.addEndpoint(RpcEndpoint("http://testnet-node.example:19544/rpc"))
        val testnet = repository.loadSettings()

        assertEquals(RpcEndpoint("http://testnet-node.example:19544/rpc"), testnet.activeEndpoint)
        assertTrue(testnet.savedEndpoints.none { it.url == "https://mainnet-explorer.example" })

        repository.setSelectedNetwork(FinalisNetwork.MAINNET)
        val mainnet = repository.loadSettings()

        assertEquals(RpcEndpoint("https://mainnet-explorer.example"), mainnet.activeEndpoint)
        assertTrue(mainnet.savedEndpoints.none { it.url.contains("testnet-node") })
        // Selection survives a fresh repository instance.
        repository.setSelectedNetwork(FinalisNetwork.TESTNET)
        assertEquals(FinalisNetwork.TESTNET, RpcSettingsRepository(preferences).selectedNetwork())
    }
    @Test
    fun `load settings ignores malformed persisted endpoints`() {
        val preferences = FakeSharedPreferences()
        preferences.edit()
            .putString(
                "saved_endpoints",
                "https://good.example/rpc\nnot-a-url\nhttps://good.example/rpc/\nftp://bad.example",
            )
            .putString("active_endpoint", "not-a-url")
            .commit()

        val repository = RpcSettingsRepository(preferences)
        val settings = repository.loadSettings()
        val expectedDefaults = (listOf(BuildConfig.EXPLORER_BASE_URL) +
            RpcSettingsRepository.BUILTIN_FALLBACK_ENDPOINTS)
            .map { normalizeRpcUrl(it, FinalisNetwork.MAINNET) }
            .distinct()

        assertEquals(
            (listOf("https://good.example") + expectedDefaults)
                .distinct()
                .map(::RpcEndpoint),
            settings.savedEndpoints,
        )
        assertEquals(RpcEndpoint("https://good.example"), settings.activeEndpoint)
    }

    @Test
    fun `classify explorer HTTP 502 as unavailable`() {
        val failure = classifyEndpointFailure(LightserverDataException("Explorer: HTTP 502"))

        assertEquals(EndpointErrorKind.UNAVAILABLE, failure.kind)
        assertEquals("Read endpoint unavailable", failure.message)
    }

    @Test
    fun `classify explorer HTTP 400 malformed address as address invalid`() {
        val failure = classifyEndpointFailure(LightserverDataException("Explorer: malformed address"))

        assertEquals(EndpointErrorKind.ADDRESS_INVALID, failure.kind)
        assertEquals("Address is invalid for this network", failure.message)
    }

    @Test
    fun `classify explorer not found as rpc error`() {
        val failure = classifyEndpointFailure(LightserverRpcException(-32001, "not found in finalized state"))

        assertEquals(EndpointErrorKind.RPC_ERROR, failure.kind)
        assertTrue(failure.message.contains("not found in finalized state", ignoreCase = true))
    }

    @Test
    fun `classify explorer parse failures as rpc error malformed data`() {
        val failure = classifyEndpointFailure(
            LightserverDataException("Explorer response parsing failed for /api/address/sc1xyz"),
        )

        assertEquals(EndpointErrorKind.MALFORMED_DATA, failure.kind)
        assertEquals("Endpoint returned malformed data", failure.message)
    }
}

private class FakeSharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): MutableSet<String>? =
        ((values[key] as? Set<String>) ?: defValues)?.toMutableSet()

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor(values)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private class Editor(
        private val target: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = values?.toSet()
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            pending[key.orEmpty()] = RemovedValue
        }

        override fun clear(): SharedPreferences.Editor = apply {
            clear = true
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) target.clear()
            pending.forEach { (key, value) ->
                if (value === RemovedValue) {
                    target.remove(key)
                } else {
                    target[key] = value
                }
            }
        }

        private companion object {
            val RemovedValue = Any()
        }
    }
}
