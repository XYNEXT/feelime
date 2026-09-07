package com.feelime.ime.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ed25519Test {
    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun acceptsRfc8032Vectors() {
        // RFC 8032 section 7.1 TEST 1..3.
        assertTrue(
            Ed25519.verify(
                hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
                ByteArray(0),
                hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
            ),
        )
        assertTrue(
            Ed25519.verify(
                hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"),
                hex("72"),
                hex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
            ),
        )
        assertTrue(
            Ed25519.verify(
                hex("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"),
                hex("af82"),
                hex("6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"),
            ),
        )
    }

    @Test
    fun rejectsTamperedMessageKeyAndSignature() {
        val publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertTrue(Ed25519.verify(publicKey, ByteArray(0), signature))
        assertFalse(Ed25519.verify(publicKey, hex("00"), signature))
        assertFalse(Ed25519.verify(hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511b"), ByteArray(0), signature))
        val flipped = signature.clone()
        flipped[10] = (flipped[10].toInt() xor 1).toByte()
        assertFalse(Ed25519.verify(publicKey, ByteArray(0), flipped))
        assertFalse(Ed25519.verify(publicKey, ByteArray(0), signature.copyOf(63)))
    }
}
