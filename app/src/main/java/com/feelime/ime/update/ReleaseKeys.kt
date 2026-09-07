package com.feelime.ime.update

/** Release signing identity; the matching private key is kept offline. */
object ReleaseKeys {
    const val KEY_ID = "feelime-release-2026-08"
    val PUBLIC_KEY: ByteArray = hex("c6988e0f52d9c2fad08037807e48fa679240c218a0ad0748feb5cdcf39f534f5")

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
