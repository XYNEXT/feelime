package com.feelime.ime.update

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Test-only RFC 8032 Ed25519 signer (production ships verify-only). Used to
 * build valid signatures for arbitrary test manifests.
 */
object TestEd25519Signer {
    private val P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val BASE = Point(
        BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
        BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
    )
    private val CLAMP_MASK = BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff8", 16)
    private val BIT_254 = BigInteger.TWO.pow(254)

    private class Point(val x: BigInteger, val y: BigInteger) {
        fun add(other: Point): Point {
            val xy = x.multiply(other.y).add(other.x.multiply(y)).mod(P)
            val yx = y.multiply(other.y).add(x.multiply(other.x)).mod(P)
            val dxy = D.multiply(x).multiply(other.x).multiply(y).multiply(other.y).mod(P)
            return Point(
                xy.multiply(BigInteger.ONE.add(dxy).modInverse(P)).mod(P),
                yx.multiply(BigInteger.ONE.subtract(dxy).modInverse(P)).mod(P),
            )
        }

        fun times(k: BigInteger): Point {
            var result = Point(BigInteger.ZERO, BigInteger.ONE)
            var addend = this
            var scalar = k
            while (scalar.signum() > 0) {
                if (scalar.testBit(0)) result = result.add(addend)
                addend = addend.add(addend)
                scalar = scalar.shiftRight(1)
            }
            return result
        }

        fun encode(): ByteArray {
            // RFC 8032 little-endian y with x sign bit in the last byte.
            val padded = ByteArray(32)
            val bytes = y.toByteArray()
            System.arraycopy(bytes, 0, padded, 32 - bytes.size, bytes.size)
            val out = ByteArray(32)
            for (index in 0 until 32) out[index] = padded[31 - index]
            if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }
    }

    /** RFC 8032 clamp: clears bits 0..2 and 255, sets bit 254. */
    private fun clamp(secret: BigInteger) = secret.and(CLAMP_MASK).or(BIT_254)

    fun publicKey(seed: ByteArray): ByteArray {
        val secret = clamp(littleEndian(sha512(seed).copyOfRange(0, 32)))
        return BASE.times(secret).encode()
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val expanded = sha512(seed)
        val secret = clamp(littleEndian(expanded.copyOfRange(0, 32)))
        val prefix = expanded.copyOfRange(32, 64)
        val publicKey = BASE.times(secret).encode()
        val r = littleEndian(sha512(prefix + message)).mod(L)
        val rEncoded = BASE.times(r).encode()
        val k = littleEndian(sha512(rEncoded + publicKey + message)).mod(L)
        val s = r.add(k.multiply(secret)).mod(L)
        return rEncoded + littleEndianFixed32(s)
    }

    private fun littleEndianFixed32(value: BigInteger): ByteArray {
        val out = ByteArray(32)
        val bytes = value.toByteArray()
        System.arraycopy(bytes, 0, out, 32 - bytes.size, bytes.size)
        return out.reversedArray()
    }

    private fun littleEndian(bytes: ByteArray): BigInteger {
        var result = BigInteger.ZERO
        for (index in bytes.indices.reversed()) {
            result = result.shiftLeft(8).or(BigInteger.valueOf(bytes[index].toLong() and 0xff))
        }
        return result
    }

    private fun sha512(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(input)
}
