# zano-kit-android

Android SDK for [Zano](https://zano.org) cryptocurrency. Wraps the Zano C++ engine via JNI and exposes a Kotlin-first API with reactive state flows.

## Requirements

- Android API 23+
- Kotlin 1.9+
- Coroutines

## Installation

Add the module to your project (local dependency or publish to a Maven repository):

```groovy
dependencies {
    implementation project(':zanokit')
}
```

## Usage

### 1. Create a wallet

**BIP39 (12 or 24-word mnemonic):**
```kotlin
val wallet = ZanoWallet.Bip39(
    mnemonic = "word1 word2 ... word12".split(" "),
    passphrase = "",
    creationTimestamp = 1700000000L, // unix timestamp of wallet creation
)
```

**Legacy (25-word Zano seed):**
```kotlin
val wallet = ZanoWallet.Legacy(
    mnemonic = "word1 word2 ... word25".split(" "),
    passphrase = "",
)
```

### 2. Initialise ZanoKit

```kotlin
val kit = ZanoKit.getInstance(
    context = context,
    wallet = wallet,
    walletId = "unique-wallet-id",
    daemonAddress = "node.zano.org:11211",
)
```

### 3. Start and stop

```kotlin
// Start syncing (suspend — call from a coroutine)
viewModelScope.launch {
    kit.start()
}

// Stop
viewModelScope.launch {
    kit.stop()
}
```

### 4. Observe sync state

```kotlin
kit.syncStateFlow.collect { state ->
    when (state) {
        is SyncState.Synced -> { /* up to date */ }
        is SyncState.Syncing -> { /* state.progress, state.remainingBlocks */ }
        is SyncState.Connecting -> { /* connecting to daemon */ }
        is SyncState.NotSynced.NoNetwork -> { /* no internet */ }
        is SyncState.NotSynced.StartError -> { /* state.message */ }
        is SyncState.NotSynced.StatusError -> { /* state.message */ }
        is SyncState.NotSynced.NotStarted -> { /* initial state */ }
    }
}
```

### 5. Observe balances

```kotlin
kit.balancesFlow.collect { balances ->
    for (balance in balances) {
        // balance.assetId, balance.total, balance.unlocked,
        // balance.awaitingIn, balance.awaitingOut
    }
}

// Native ZANO balance shortcut
val zano: BalanceInfo = kit.nativeBalance
```

Amounts are in atomic units (12 decimal places). Divide by `1_000_000_000_000L` to display.

### 6. Observe transactions

```kotlin
kit.transactionsFlow.collect { txs ->
    for (tx in txs) {
        // tx.hash, tx.type (incoming/outgoing/sentToSelf),
        // tx.amount, tx.fee, tx.timestamp, tx.memo,
        // tx.isPending, tx.assetId
    }
}

// Query with filters
val incoming = kit.transactions(
    assetId = ZanoKit.ZANO_ASSET_ID,
    type = TransactionFilterType.incoming,
    descending = true,
    limit = 20,
)
```

### 7. Send

```kotlin
viewModelScope.launch {
    try {
        val txHash = kit.send(
            toAddress = "ZxABC...",
            amount = SendAmount.Value(1_000_000_000_000L), // 1 ZANO
            memo = "payment",
        )
    } catch (e: InsufficientFundsException) {
        // not enough balance
    } catch (e: SendFailedException) {
        // transfer rejected by daemon
    }
}

// Send entire unlocked balance
kit.send(toAddress = "ZxABC...", amount = SendAmount.All)
```

### 8. Derive address offline

Derive the wallet's public address without opening or syncing a wallet file:

```kotlin
val address: String? = ZanoKit.address(wallet)
```

### 9. Utilities

```kotlin
// Validate an address
ZanoKit.isValidAddress("ZxABC...")

// Estimate fee
val fee: Long = kit.estimateFee(SendPriority.default)

// Restore height for a date (for seeding creationTimestamp)
val height: Long = ZanoKit.restoreHeightForDate(Date())

// Receive address
val address: String = kit.receiveAddress
```

## Wallet types

| | BIP39 | Legacy |
|-|-------|--------|
| Seed format | 12 or 24 words + optional passphrase | 25 words (Electrum-style) |
| `creationTimestamp` | Required — unix timestamp of wallet creation | Decoded automatically from word 25 |
| Derivation | secp256k1 HMAC-SHA512 (BIP32 `m/44'/128'/0'/0/0`) | Electrum seed restore |

## Error types

| Exception | Meaning |
|-----------|---------|
| `InsufficientFundsException` | Not enough unlocked balance |
| `SendFailedException` | Transfer rejected by the daemon |
| `ZanoException` | Base class for all Zano errors |

## Supported ABIs

`arm64-v8a`, `armeabi-v7a`, `x86_64`
