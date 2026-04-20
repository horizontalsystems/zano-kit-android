package io.horizontalsystems.zanokit.sample

// Replace with your own test wallet seed and a reachable Zano node.
// Legacy seed = 25 words; BIP39 seed = 24 words + optional passphrase.
object WalletConfig {
    val SEED = "melt veteran patrol echo miss fat wrap apple crowd eyebrow weird boring".split(" ")
    const val PASSPHRASE = ""

    // Unix timestamp of wallet creation (0 = scan from genesis; set to actual creation time to speed up sync)
    const val CREATION_TIMESTAMP = 0L

    const val WALLET_ID = "demo-wallet-001"

    // Public Zano mainnet node
//        const val DAEMON_ADDRESS = "37.27.100.59:10500"
        const val DAEMON_ADDRESS = "https://zano.unstoppable.money:443"
//    const val DAEMON_ADDRESS = "37.27.98.156:11211"
}
