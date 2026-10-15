package io.github.jqssun.btgps.iap2

import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature

class SoftwareAuth(
    val certificate: ByteArray,
    val serial: ByteArray,
    private val privateKey: PrivateKey,
) {
    fun sign(challenge: ByteArray): ByteArray {
        require(challenge.size == 32) { "v3 expects a 32-byte digest" }
        return derToP1363(_signDer(challenge))
    }

    fun matches(publicKey: PublicKey): Boolean {
        val probe = ByteArray(32).also(SecureRandom()::nextBytes)
        val verifier = Signature.getInstance(ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(probe)
        return verifier.verify(_signDer(probe))
    }

    private fun _signDer(data: ByteArray): ByteArray {
        val signer = Signature.getInstance(ALGORITHM)
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    private companion object {
        const val ALGORITHM = "NONEwithECDSA"
    }
}

internal fun derToP1363(der: ByteArray, size: Int = 32): ByteArray {
    var index = 2
    if ((der[1].toInt() and 0xFF) == 0x81) index = 3
    require(der[index] == 0x02.toByte())
    val rLength = der[index + 1].toInt() and 0xFF
    val r = der.copyOfRange(index + 2, index + 2 + rLength)
    val sIndex = index + 2 + rLength
    require(der[sIndex] == 0x02.toByte())
    val sLength = der[sIndex + 1].toInt() and 0xFF
    val s = der.copyOfRange(sIndex + 2, sIndex + 2 + sLength)
    return integerToFixed(r, size) + integerToFixed(s, size)
}

private fun integerToFixed(value: ByteArray, size: Int): ByteArray {
    var start = 0
    while (start < value.size - 1 && value[start] == 0.toByte()) start++
    val length = value.size - start
    require(length <= size) { "ECDSA integer longer than $size" }
    val out = ByteArray(size)
    value.copyInto(out, destinationOffset = size - length, startIndex = start)
    return out
}
