package com.feelime.ime.update

import java.math.BigInteger

/**
 * Minimal RFC 8032 Ed25519 verifier (BigInteger-based: one signature per
 * keyboard update, so throughput is irrelevant and correctness is everything).
 * Only VERIFY is implemented — signing lives in the offline release tooling.
 */
object Ed25519 {
    private val P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L = BigInteger.TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val SQRT_M1 = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    // Standard base point (RFC 8032 section 5.1).
    private val BASE = Point(
        BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
        BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
    )

    /** Affine point (x, y) on the twisted Edwards curve -x^2+y^2=1+dx^2y^2. */
    private class Point(val x: BigInteger, val y: BigInteger) {
        fun add(other: Point): Point {
            // RFC 8032 section 5.1.4 affine addition (unified; doubling included).
            val xy = x.multiply(other.y).add(other.x.multiply(y)).mod(P)
            val yx = y.multiply(other.y).add(x.multiply(other.x)).mod(P)
            val dxy = D.multiply(x.multiply(other.x).multiply(y).multiply(other.y)).mod(P)
            val x3 = xy.multiply(BigInteger.ONE.add(dxy).modInverse(P)).mod(P)
            val y3 = yx.multiply(BigInteger.ONE.subtract(dxy).modInverse(P)).mod(P)
            return Point(x3, y3)
        }

        fun scalarMultiply(k: BigInteger): Point {
            var result = Point(BigInteger.ZERO, BigInteger.ONE)
            var addend = this
            var scalar = k.mod(L)
            while (scalar.signum() > 0) {
                if (scalar.testBit(0)) result = result.add(addend)
                addend = addend.add(addend)
                scalar = scalar.shiftRight(1)
            }
            return result
        }

        fun encode(): ByteArray {
            // RFC 8032: y little-endian with the x sign bit in the top bit of
            // the last byte. toByteArray() is big-endian, so pad then reverse.
            val padded = ByteArray(32)
            val bytes = y.toByteArray()
            System.arraycopy(bytes, 0, padded, 32 - bytes.size, bytes.size)
            val out = ByteArray(32)
            for (index in 0 until 32) out[index] = padded[31 - index]
            if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }

        fun onCurve(): Boolean {
            val xx = x.multiply(x).mod(P)
            val yy = y.multiply(y).mod(P)
            val left = yy.subtract(xx).mod(P)
            val right = BigInteger.ONE.add(D.multiply(xx).multiply(yy)).mod(P)
            return left == right
        }
    }

    private fun decodePoint(encoded: ByteArray): Point? {
        if (encoded.size != 32) return null
        val yBytes = encoded.clone()
        val xSign = (yBytes[31].toInt() and 0x80) != 0
        yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
        val y = littleEndian(yBytes)
        if (y >= P) return null
        val yy = y.multiply(y).mod(P)
        val u = yy.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(yy).add(BigInteger.ONE).mod(P)
        val v3 = v.multiply(v).multiply(v).mod(P)
        val v7 = v3.multiply(v3).multiply(v).mod(P)
        var x = u.multiply(v3)
            .multiply(u.multiply(v7).modPow(P.subtract(BigInteger.valueOf(5)).divide(BigInteger.valueOf(8)), P))
            .mod(P)
        val vx2 = v.multiply(x).multiply(x).mod(P)
        val minusU = P.subtract(u)
        x = when {
            vx2 == u -> x
            vx2 == minusU -> x.multiply(SQRT_M1).mod(P)
            else -> return null
        }
        if (x.signum() == 0 && xSign) return null
        if (x.testBit(0) != xSign) x = P.subtract(x)
        return Point(x, y).takeIf { it.onCurve() }
    }

    /** Verifies an Ed25519 signature over the message with the raw public key. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decodePoint(publicKey) ?: return false
        val rEncoded = signature.copyOfRange(0, 32)
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val r = decodePoint(rEncoded) ?: return false
        val h = littleEndian(sha512(rEncoded + publicKey + message)).mod(L)
        val left = BASE.scalarMultiply(s)
        val right = r.add(a.scalarMultiply(h))
        return constantTimeEquals(left.encode(), right.encode())
    }

    private fun littleEndian(bytes: ByteArray): BigInteger {
        var result = BigInteger.ZERO
        for (index in bytes.indices.reversed()) {
            result = result.shiftLeft(8).or(BigInteger.valueOf(bytes[index].toLong() and 0xff))
        }
        return result
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (index in a.indices) diff = diff or (a[index].toInt() xor b[index].toInt())
        return diff == 0
    }

    private fun sha512(input: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-512").digest(input)
}
