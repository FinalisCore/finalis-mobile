package com.finalis.mobile.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FinalisKeyDerivationTest {
    @Test
    fun `normalizes uppercase private key hex`() {
        val normalized = FinalisKeyDerivation.normalizePrivateKeyHex(
            "  000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F  "
        )

        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", normalized)
    }

    @Test
    fun `rejects invalid private key length`() {
        assertFailsWith<InvalidPrivateKeyException> {
            FinalisKeyDerivation.normalizePrivateKeyHex("abcd")
        }
    }

    @Test
    fun `rejects invalid private key hex`() {
        assertFailsWith<InvalidPrivateKeyException> {
            FinalisKeyDerivation.normalizePrivateKeyHex("zz".repeat(32))
        }
    }

    @Test
    fun `accepts zero seed because v1 private key is a 32-byte ed25519 seed`() {
        val normalized = FinalisKeyDerivation.normalizePrivateKeyHex("00".repeat(32))

        assertEquals("00".repeat(32), normalized)
    }

    @Test
    fun `derives vector public key and address`() {
        val keyMaterial = FinalisKeyDerivation.deriveKeyMaterial(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        )
        val address = FinalisAddressCodec.deriveAddressFromPublicKey(keyMaterial.publicKeyHex, hrp = "sc")

        assertEquals("03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8", keyMaterial.publicKeyHex)
        assertEquals("sc1adrqzotqb6xl76opjawtlcn5ds6msa6sqaw7dj7p", address)
        assertTrue(address.startsWith("sc1"))
    }

    @Test
    fun `derives testnet tsc address matching core address_from_pubkey`() {
        val keyMaterial = FinalisKeyDerivation.deriveKeyMaterial(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        )
        val address = FinalisAddressCodec.deriveAddressFromPublicKey(keyMaterial.publicKeyHex, hrp = "tsc")

        // finalis-cli address_from_pubkey --hrp tsc --pubkey 03a107bf…31b8
        assertEquals("tsc1adrqzotqb6xl76opjawtlcn5ds6msa6sqdtecmte", address)
        assertEquals("tsc", FinalisAddressCodec.decodeAddress(address).hrp)
    }
}
