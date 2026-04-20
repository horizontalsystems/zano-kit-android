package io.horizontalsystems.zanokit

import io.horizontalsystems.zanokit.util.RestoreHeight

sealed class ZanoWallet {

    class Bip39(
        val mnemonic: List<String>,
        val passphrase: String,
        val creationTimestamp: Long,
    ) : ZanoWallet()

    class Legacy(
        val mnemonic: List<String>,
        val passphrase: String,
    ) : ZanoWallet()

    val seed: String
        get() = when (this) {
            is Bip39 -> mnemonic.joinToString(" ")
            is Legacy -> mnemonic.joinToString(" ")
        }

    val seedPassword: String
        get() = when (this) {
            is Bip39 -> passphrase
            is Legacy -> passphrase
        }

    val restoreHeight: Long
        get() = when (this) {
            is Bip39 -> RestoreHeight.getHeight(creationTimestamp)
            is Legacy -> {
                val timestampWord = if (mnemonic.size >= 25) mnemonic[24] else ""
                RestoreHeight.getHeight(ZanoNative.getTimestampFromWord(timestampWord))
            }
        }
}
