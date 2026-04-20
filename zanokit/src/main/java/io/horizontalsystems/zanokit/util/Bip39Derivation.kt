package io.horizontalsystems.zanokit.util

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.crypto.ec.CustomNamedCurves
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val SECP256K1: X9ECParameters = CustomNamedCurves.getByName("secp256k1")
private val CURVE = ECDomainParameters(SECP256K1.curve, SECP256K1.g, SECP256K1.n, SECP256K1.h)

// ed25519 curve order (little-endian: l = 2^252 + 27742317777372353535851937790883648493)
private val ED25519_ORDER = BigInteger(
    "1000000000000000000000000000000014DEF9DEA2F79CD65812631A5CF5D3ED", 16
)

/**
 * Derives the Zano secret key from a BIP39 mnemonic.
 *   1. PBKDF2-HMAC-SHA512(mnemonic, "mnemonic" + passphrase, 2048 iterations) → 64-byte seed
 *   2. BIP32 derivation: m / 44' / 128' / 0' / 0 / 0  (secp256k1)
 *   3. Reduce 32-byte private key mod ed25519 curve order (little-endian encoding)
 * Returns lowercase hex string of the 32-byte reduced key.
 */
fun deriveZanoSecretKey(mnemonic: List<String>, passphrase: String): String {
    val seed = mnemonicToSeed(mnemonic, passphrase)
    val (masterKey, masterChain) = masterKeyFromSeed(seed)
    val path = listOf(
        0x8000002CL,  // 44'
        0x80000080L,  // 128'
        0x80000000L,  // 0'
        0L,           // 0
        0L,           // 0
    )
    var key = masterKey
    var chain = masterChain
    for (index in path) {
        val (childKey, childChain) = deriveChild(key, chain, index)
        key = childKey
        chain = childChain
    }
    return reduceKeyLittleEndian(key).toHex()
}

private fun mnemonicToSeed(mnemonic: List<String>, passphrase: String): ByteArray {
    val gen = PKCS5S2ParametersGenerator(SHA512Digest())
    val password = mnemonic.joinToString(" ").toByteArray(Charsets.UTF_8)
    val salt = ("mnemonic$passphrase").toByteArray(Charsets.UTF_8)
    gen.init(password, salt, 2048)
    return (gen.generateDerivedParameters(512) as KeyParameter).key
}

private fun masterKeyFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
    val mac = HMac(SHA512Digest())
    mac.init(KeyParameter("Bitcoin seed".toByteArray(Charsets.UTF_8)))
    mac.update(seed, 0, seed.size)
    val out = ByteArray(64)
    mac.doFinal(out, 0)
    return out.copyOf(32) to out.copyOfRange(32, 64)
}

private fun deriveChild(parentKey: ByteArray, parentChain: ByteArray, index: Long): Pair<ByteArray, ByteArray> {
    val mac = HMac(SHA512Digest())
    mac.init(KeyParameter(parentChain))

    val data = if (index >= 0x80000000L) {
        // Hardened: 0x00 || parentKey || index (big-endian uint32)
        ByteArray(37).also { buf ->
            buf[0] = 0x00
            parentKey.copyInto(buf, 1)
            ByteBuffer.wrap(buf, 33, 4).order(ByteOrder.BIG_ENDIAN).putInt(index.toInt())
        }
    } else {
        // Non-hardened: compressed pubkey (33 bytes) || index
        val pubKey = compressedPublicKey(parentKey)
        ByteArray(37).also { buf ->
            pubKey.copyInto(buf, 0)
            ByteBuffer.wrap(buf, 33, 4).order(ByteOrder.BIG_ENDIAN).putInt(index.toInt())
        }
    }

    mac.update(data, 0, data.size)
    val out = ByteArray(64)
    mac.doFinal(out, 0)

    val IL = BigInteger(1, out.copyOf(32))
    val parentKeyInt = BigInteger(1, parentKey)
    val childKeyInt = IL.add(parentKeyInt).mod(CURVE.n)
    val childKey = childKeyInt.toByteArray32()
    val childChain = out.copyOfRange(32, 64)
    return childKey to childChain
}

private fun compressedPublicKey(privateKey: ByteArray): ByteArray {
    val privInt = BigInteger(1, privateKey)
    val point = CURVE.g.multiply(privInt).normalize()
    return point.getEncoded(true)  // compressed: 33 bytes
}

// Reads 32 bytes as little-endian BigInteger, reduces mod ed25519 order,
// writes result back as little-endian 32 bytes.
private fun reduceKeyLittleEndian(key: ByteArray): ByteArray {
    val le = key.copyOf(32).also { it.reverse() }  // reverse to big-endian for BigInteger
    val n = BigInteger(1, le)
    var result = n.mod(ED25519_ORDER)
    val out = ByteArray(32)
    for (i in 0 until 32) {
        out[i] = (result.and(BigInteger.valueOf(0xFF))).toByte()
        result = result.shiftRight(8)
    }
    return out
}

private fun BigInteger.toByteArray32(): ByteArray {
    val bytes = toByteArray()
    return when {
        bytes.size == 32 -> bytes
        bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
        else -> ByteArray(32).also { bytes.copyInto(it, 32 - bytes.size) }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
