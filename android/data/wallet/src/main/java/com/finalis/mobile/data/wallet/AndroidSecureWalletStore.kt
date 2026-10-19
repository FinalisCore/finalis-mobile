package com.finalis.mobile.data.wallet

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.finalis.mobile.core.model.FinalisNetwork
import com.finalis.mobile.core.model.ImportedWalletRecord
import com.finalis.mobile.core.model.WalletAddress
import com.finalis.mobile.core.model.WalletProfile
import com.finalis.mobile.core.storage.WalletStore

class AndroidSecureWalletStore(
    context: Context,
) : WalletStore {
    private val appContext = context.applicationContext

    private val preferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            appContext,
            PreferencesFileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): ImportedWalletRecord? {
        val safePreferences = runCatching { preferences }.getOrNull() ?: return null
        val privateKeyHex = runCatching { safePreferences.getString(KeyPrivateKeyHex, null) }.getOrNull() ?: return null
        val address = runCatching { safePreferences.getString(KeyAddress, null) }.getOrNull() ?: return null
        if (privateKeyHex.isBlank() || address.isBlank()) return null
        val publicKeyHex = runCatching { safePreferences.getString(KeyPublicKeyHex, null) }.getOrNull().orEmpty()
        val label = runCatching { safePreferences.getString(KeyLabel, null) }.getOrNull() ?: "Primary wallet"
        val importedAt = runCatching { safePreferences.getLong(KeyImportedAtEpochMillis, 0L) }.getOrDefault(0L)
        val storedNetworkName = runCatching { safePreferences.getString(KeyNetworkName, null) }.getOrNull()
        // Records written before network selection existed carry no network name and are mainnet.
        val network = FinalisNetwork.fromName(storedNetworkName)
            ?: FinalisNetwork.fromAddress(address)
            ?: FinalisNetwork.MAINNET

        return ImportedWalletRecord(
            privateKeyHex = privateKeyHex,
            walletProfile = WalletProfile(
                address = WalletAddress(address),
                publicKeyHex = publicKeyHex,
                label = label,
                network = network,
            ),
            importedAtEpochMillis = importedAt,
        )
    }

    override fun save(record: ImportedWalletRecord) {
        val committed = preferences.edit()
            .putString(KeyPrivateKeyHex, record.privateKeyHex)
            .putString(KeyAddress, record.walletProfile.address.value)
            .putString(KeyPublicKeyHex, record.walletProfile.publicKeyHex)
            .putString(KeyLabel, record.walletProfile.label)
            .putLong(KeyImportedAtEpochMillis, record.importedAtEpochMillis)
            .putString(KeyNetworkName, record.walletProfile.network.name)
            .remove(KeyAddressHrp)
            .remove(KeyExpectedNetworkId)
            .remove(KeyExpectedGenesisHash)
            .commit()
        check(committed) { "Failed to persist wallet record" }
    }

    override fun clear() {
        val committed = preferences.edit().clear().commit()
        check(committed) { "Failed to clear wallet record" }
    }

    private companion object {
        const val PreferencesFileName = "finalis_secure_wallet"
        const val KeyPrivateKeyHex = "private_key_hex"
        const val KeyAddress = "wallet_address"
        const val KeyPublicKeyHex = "public_key_hex"
        const val KeyLabel = "wallet_label"
        const val KeyImportedAtEpochMillis = "imported_at_epoch_millis"
        const val KeyNetworkName = "network_name"
        const val KeyAddressHrp = "address_hrp"
        const val KeyExpectedNetworkId = "expected_network_id"
        const val KeyExpectedGenesisHash = "expected_genesis_hash"
    }
}
