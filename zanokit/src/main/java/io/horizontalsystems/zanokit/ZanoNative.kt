package io.horizontalsystems.zanokit

object ZanoNative {
    init {
        System.loadLibrary("zanokit")
    }

    // Library lifecycle
    external fun init2(ip: String, port: String, workingDir: String, logLevel: Int): String?
    external fun deinit()
    external fun getVersion(): String?

    // Wallet file operations
    external fun isWalletExist(path: String): Boolean
    external fun openWallet(path: String, password: String): String?
    external fun restoreWallet(seed: String, path: String, password: String, seedPassword: String): String?
    external fun generateWallet(path: String, password: String): String?
    external fun closeWallet(walletId: Long): String?

    // Core wallet operations
    external fun invoke(walletId: Long, params: String): String?
    external fun getWalletStatus(walletId: Long): String?
    external fun syncCall(method: String, instanceId: Long, params: String): String?

    // Utilities
    external fun getCurrentTxFee(priority: Long): Long
    external fun getTimestampFromWord(word: String): Long
    external fun generateAddress(seed: String, seedPassword: String): String?
    external fun generateAddressFromDerivation(secretDerivationHex: String, isAuditable: Boolean): String?
    external fun getAddressInfo(address: String): String?
    external fun getWalletInfo(walletId: Long): String?
    external fun resetWalletPassword(walletId: Long, password: String): String?
}
