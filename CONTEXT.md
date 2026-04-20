# zano-kit-android

An Android SDK for Zano cryptocurrency integration. Two-module Gradle project:
- **`zanokit/`** — the library module
- **`app/`** — demo app (Jetpack Compose, 3 screens: Balance, Transactions, Send)

**Language:** Pure Kotlin. No Java.

---

## Native Library Stack

Zano's C++ engine is cross-compiled into static `.a` libraries and statically linked into a single JNI shared library `libzanokit.so` at build time.

```
Kotlin (ZanoNative.kt)
    ↓  JNI calls
zanokit/src/main/cpp/zano_jni.cpp       ← JNI bridge (Java_io_horizontalsystems_zanokit_ZanoNative_*)
zanokit/src/main/cpp/wallet2_api_c.cpp  ← C wrapper around plain_wallet:: API
zanokit/src/main/cpp/helpers.cpp        ← utility functions
    ↓  links against
zanokit/external-libs/{ABI}/*.a         ← 17 prebuilt static libs per ABI
```

`wallet2_api_c.cpp` and `helpers.cpp` wrap the C++ `plain_wallet::` namespace into C-linkage `ZANO_PlainWallet_*` functions that the JNI bridge calls.

### Static Libraries Per ABI

| Library | Contents |
|---------|----------|
| `libwallet.a` | Zano wallet engine (`plain_wallet::` API) |
| `libcurrency_core.a` | Zano currency and account logic |
| `libcommon.a` | Zano common utilities |
| `libzano_crypto.a` | Zano crypto primitives (renamed from `libcrypto.a` — see below) |
| `libz.a` | zlib |
| `libboost_atomic/chrono/date_time/filesystem/program_options/regex/serialization/system/thread/timer/wserialization.a` | Boost 1.84.0 (11 libs) |
| `libssl.a` | OpenSSL 3.1.8 |
| `libcrypto.a` | OpenSSL 3.1.8 |

ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`

**Why `libzano_crypto.a`:** Zano's build produces a `libcrypto.a` (~1.7 MB) for its own crypto primitives. OpenSSL also produces `libcrypto.a` (~10 MB). Both are needed. The copy step renames Zano's to `libzano_crypto.a` so they can coexist; `CMakeLists.txt` declares them as separate CMake targets.

---

## Cross-Compiling the Native Libraries

These steps produce the `.a` files in `zanokit/external-libs/`. Run them whenever updating the Zano source version or reapplying patches.

### Prerequisites

- macOS with Xcode command line tools
- Android Studio with:
  - NDK `27.0.12077973` (r27)
  - CMake `3.22.1`
- CMake on PATH: `export PATH="$HOME/Library/Android/sdk/cmake/3.22.1/bin:$PATH"`
- NDK env var: `export ANDROID_NDK_ROOT=$HOME/Library/Android/sdk/ndk/27.0.12077973`

### Step 1 — Clone the build system and Zano source

```bash
git clone --recursive https://github.com/hyle-team/zano_native_lib ~/zano_native_lib
cd ~/zano_native_lib

# Boost-for-Android submodule uses SSH — clone manually if it failed above:
git clone https://github.com/moritz-wundke/Boost-for-Android.git thirdparty/boost-android

# Zano requires tor headers even when TOR is disabled:
cd Zano
git submodule update --init contrib/tor-connect
cd ..
```

### Step 2 — Build OpenSSL 3.1.8

```bash
cd ~/zano_native_lib
export ANDROID_NDK_ROOT=$HOME/Library/Android/sdk/ndk/27.0.12077973
./thirdparty/build-openssl-android.sh
```

Output: `_libs_android/openssl/{arm64-v8a,armeabi-v7a,x86,x86_64}/lib/{libssl,libcrypto}.a`

### Step 3 — Build Boost 1.84.0

```bash
cd ~/zano_native_lib
./thirdparty/build-boost-android.sh
```

Output: `_libs_android/boost/{arm64-v8a,armeabi-v7a,x86,x86_64}/lib/libboost_*.a` and `_libs_android/boost/include/`

Boost libraries built: atomic, chrono, date_time, filesystem, program_options, regex, serialization, system, thread, timer, wserialization.

### Step 4 — Apply patches to the Zano source

```bash
cd ~/zano_native_lib/Zano
git am ~/StudioProjects/zano-kit-android/patches/0001-Add-generate_address-and-generate_address_from_deriv.patch
```

See [Patches](#patches) below for what this changes.

### Step 5 — Build the Zano libraries

```bash
cd ~/zano_native_lib
export PATH="$HOME/Library/Android/sdk/cmake/3.22.1/bin:$PATH"
export ANDROID_NDK_ROOT=$HOME/Library/Android/sdk/ndk/27.0.12077973
./build_android_libs.sh
```

`build_android_libs.sh` iterates over all four ABIs (`armeabi-v7a`, `x86`, `arm64-v8a`, `x86_64`) and runs CMake for each with these flags:
```
-DCMAKE_SYSTEM_NAME=Android
-DCMAKE_SYSTEM_VERSION=23
-DCMAKE_ANDROID_STL_TYPE=c++_static
-DDISABLE_TOR=TRUE
-DCMAKE_C_FLAGS=-mno-unaligned-access   (armeabi-v7a only)
```

Output: `_install_android/{ABI}/lib/{libwallet,libcurrency_core,libcommon,libcrypto,libz}.a`

### Step 6 — Copy libraries and headers into the project

```bash
for ABI in arm64-v8a armeabi-v7a x86_64; do
  DEST=~/StudioProjects/zano-kit-android/zanokit/external-libs/$ABI
  mkdir -p $DEST

  # Zano libs (rename libcrypto.a to avoid collision with OpenSSL)
  cp ~/zano_native_lib/_install_android/$ABI/lib/libwallet.a        $DEST/
  cp ~/zano_native_lib/_install_android/$ABI/lib/libcurrency_core.a $DEST/
  cp ~/zano_native_lib/_install_android/$ABI/lib/libcommon.a        $DEST/
  cp ~/zano_native_lib/_install_android/$ABI/lib/libcrypto.a        $DEST/libzano_crypto.a
  cp ~/zano_native_lib/_install_android/$ABI/lib/libz.a             $DEST/

  # Boost
  cp ~/zano_native_lib/_libs_android/boost/$ABI/lib/*.a $DEST/

  # OpenSSL
  cp ~/zano_native_lib/_libs_android/openssl/$ABI/lib/libssl.a    $DEST/
  cp ~/zano_native_lib/_libs_android/openssl/$ABI/lib/libcrypto.a $DEST/
done

# Headers (architecture-independent — copy from any ABI)
cp ~/zano_native_lib/_install_android/arm64-v8a/include/* \
   ~/StudioProjects/zano-kit-android/zanokit/external-libs/include/
```

The `external-libs/include/` directory also contains `wallet2_api_c.h` and `plain_wallet_api.h`. These are the patched versions that declare `generate_address`, `generate_address_from_derivation`, and `get_timestamp_from_word`. Do not overwrite them with unpatched headers from the Zano install.

---

## Patches

All patches are in `patches/` and must be applied to the Zano source (Step 4) before building.

### `0001-Add-generate_address-and-generate_address_from_deriv.patch`

These three functions exist in `plain_wallet_api.h` (the external-libs header) but were not in the upstream `hyle-team/zano` source. The patch adds them.

**`src/currency_core/account.h`**
- Moves `void set_null()` from `private:` to `public:`. Required so the new `plain_wallet` functions can zero out the account object after extracting the address.

**`src/wallet/plain_wallet_api.h`**
- Declares three new functions in the `plain_wallet` namespace:
  - `uint64_t get_timestamp_from_word(const std::string& word, bool& password_used)` — decodes the creation timestamp from a legacy seed word
  - `std::string generate_address(const std::string& seed, const std::string& seed_password)` — derives the wallet address from a 25-word legacy seed without opening a full wallet
  - `std::string generate_address_from_derivation(const std::string& secret_derivation_hex, bool is_auditable)` — derives the wallet address from a BIP39 secret derivation hex without opening a full wallet

**`src/wallet/plain_wallet_api.cpp`**
- Implements the three functions declared above.
- `generate_address`: constructs a `currency::account_base`, calls `restore_from_seed_phrase`, extracts the address string, then zeroes the account with `set_null()`.
- `generate_address_from_derivation`: hex-decodes the derivation key with `epee::string_tools::parse_hexstr_to_binbuff`, calls `restore_from_secret_derivation`, extracts the address, then zeroes the account.
- `get_timestamp_from_word`: delegates to `currency::get_timestamp_from_word` (already in `currency_format_utils`).

---

## CMakeLists.txt (`zanokit/CMakeLists.txt`)

- Compiles three C++ sources into `libzanokit.so`: `zano_jni.cpp`, `wallet2_api_c.cpp`, `helpers.cpp`
- All 17 `.a` libs declared as `STATIC IMPORTED` targets with paths `${EXTERNAL_LIBS_DIR}/${ANDROID_ABI}/lib*.a`
- Link order: Zano libs → Boost → OpenSSL → system `log`
- `target_compile_definitions(zanokit PRIVATE ZANO_LIBS_AVAILABLE=1)` — enables the real JNI implementation (vs. stub mode)
- Linker flag: `-Wl,-z,max-page-size=16384` — required for Android 15+ 16 KB page size

## build.gradle (`zanokit/build.gradle`)

```groovy
ndkVersion '27.0.12077973'
cppFlags "-std=c++17"
arguments '-DANDROID_STL=c++_static'  // must match how Zano libs were compiled
abiFilters "armeabi-v7a", "arm64-v8a", "x86_64"
```

`c++_static` STL must match Zano's compilation. Mixing `c++_static` and `c++_shared` causes crashes at runtime.

---

## Kotlin SDK

```
zanokit/src/main/java/io/horizontalsystems/zanokit/
├── ZanoNative.kt          external fun declarations + System.loadLibrary("zanokit")
├── ZanoWalletApi.kt       JSON-RPC wrapper over ZanoNative (invoke, getBalances, transfer, etc.)
├── ZanoWallet.kt          sealed class: Bip39 / Legacy — restoreHeight computed property, clear()
├── Models.kt              data classes + sealed classes (SyncState, AssetInfo, BalanceInfo, etc.)
├── ZanoCore.kt            wallet lifecycle: start/stop/refresh, fetchBalances, fetchTransactions
├── SyncStateManager.kt    5s polling loop, network reachability guard, evaluateState() → SyncState
├── KitManager.kt          Mutex singleton — enforces one active kit at a time
├── ZanoKit.kt             public API facade; address(wallet) for offline address derivation
├── util/
│   ├── RestoreHeight.kt   date → block height lookup (checkpoint table May 2019–Apr 2026)
│   └── Bip39Derivation.kt deriveZanoSecretKey — BIP39 mnemonic → 64-char hex via BouncyCastle secp256k1
└── storage/
    ├── ZanoDatabase.kt    Room database (version 1)
    ├── ZanoStorage.kt     storage abstraction
    ├── dao/               AssetDao, BalanceDao, TransactionDao, SentTransferDao, WalletInfoDao
    └── entities/          AssetEntity, BalanceEntity, TransactionEntity (PK: hash+assetId),
                           SentTransferEntity, WalletInfoEntity
```

### Wallet Types

`ZanoWallet` is a sealed class with two subtypes:

- **`ZanoWallet.Legacy`** — 25-word Electrum-style seed. Restored via `ZanoNative.restoreWallet(seed, path, password, seedPassword)`. `restoreHeight` is decoded from word 24 of the seed using `get_timestamp_from_word`.
- **`ZanoWallet.Bip39`** — 12 or 24-word BIP39 mnemonic with optional passphrase. The mnemonic is derived to a 64-char hex key via `deriveZanoSecretKey` (secp256k1 HMAC-SHA512, BouncyCastle). Restored via `ZanoNative.syncCall("restore_from_derivations", ...)`. `restoreHeight` comes from `creationTimestamp` passed at construction.

Both subtypes have a `clear()` method that zeroes the mnemonic list and passphrase string after the native wallet opens successfully.

### Offline Address Derivation

`ZanoKit.address(wallet: ZanoWallet): String?` derives the wallet's public address without opening a full wallet file. It calls:
- `ZanoNative.generateAddress(seed, seedPassword)` for Legacy wallets
- `ZanoNative.generateAddressFromDerivation(hex, false)` for BIP39 wallets (hex from `deriveZanoSecretKey`)

These map to the patched `plain_wallet::generate_address` and `plain_wallet::generate_address_from_derivation` compiled into `libwallet.a`.

### JSON-RPC Flow

All `ZanoNative.*` calls return JSON strings. Format:
```json
{"result": {...}, "error": {"code": 0, "message": ""}}
```
`ZanoWalletApi.parseResponse()` parses these — non-zero `error.code` throws `ZanoException`.

`ZanoNative.invoke(walletId, jsonRequest)` is the main method for wallet operations:
- `getbalance` → `balances[].{asset_info, total, unlocked, awaiting_in, awaiting_out}`
- `get_recent_txs_and_info` → `transfers[].{tx_hash, subtransfers[], employed_entries, ...}`
- `transfer` → `{tx_hash}` — throws `InsufficientFundsException` or `SendFailedException` on error

### Transaction Logic

Zano transactions have a `subtransfers[]` array — one entry per asset involved:
1. Skip the fee subtransfer: outgoing ZANO entry where `amount == fee`
2. Outgoing native ZANO: displayed amount is `amount - fee` (net of fee)
3. All other subtransfers: displayed amount is `amount` as-is
4. Self-send detection: when no subtransfers produce records for an outgoing tx, inspect `employed_entries.{receive, spent}` — if received + fee == spent for an asset, it's a `sentToSelf`

### SyncState Machine

```
NotStarted
    ↓ start()
NoNetwork ←→ Connecting(waiting=false)
                ↓ daemon connected, daemonHeight > 0
              [walletHeight + 2 >= daemonHeight] → Synced  (±2 tolerance: daemon tip is always ~1 block ahead of wallet)
              [behind]                           → Syncing(progress, remainingBlocks)
              [30s elapsed, no connection]       → StatusError
```

- Polls every 5 seconds via coroutine loop
- `onSyncedPoll` fires on each new block when `Synced`, triggering `refresh()` (fetch balances + transactions)
- `api.store()` called on every `Synced` state and every 2000 blocks during `Syncing`
- Network reachability monitored via `ConnectivityManager.NetworkCallback` — transitions to `NoNetwork` immediately on connectivity loss, resumes `Connecting` when network returns

### BIP39 Timestamp Mismatch Recovery

On BIP39 wallet open, `ZanoCore` compares the wallet's `creationTimestamp` against the value stored in the Room DB. If they differ, the wallet directory and DB are wiped and `start()` retries once. This handles the case where a wallet was previously synced from a different restore height. The exception type is `RestoreHeightDontMatchException`; `ZanoKit.start()` catches it, deletes `core.walletDirPath()`, calls `storage.clearAll()`, then calls `core.start()` again.

### Wallet File Layout on Device

```
filesDir/ZanoKit/{walletId}/network_{0|1}/
├── zano_core/
│   └── wallet          ← Zano binary wallet file
└── storage             ← Room database
```

---

## Zano Facts

| | |
|-|-|
| Native asset ID | `d6329b5b1f7c0805b5c345f4957554002a2f557845f64d7645dae0e051a6498a` |
| Decimal places | 12 |
| Block time | ~60 seconds |
| Legacy seed | 25 words (Electrum-style) |
| BIP39 seed | 12 or 24 words + optional passphrase |
| Wallet handle | `wallet_id: Long` returned by open/restore response |
| Sync model | Polling-based, 5s interval (no push) |
| Multi-asset | Yes — each asset has independent balance and transaction records |
| Restore height | Unix timestamp |

---

## Demo App

```
app/src/main/java/io/horizontalsystems/zanokit/sample/
├── MainActivity.kt           bottom nav (3 tabs)
├── MainViewModel.kt          AndroidViewModel, creates ZanoKit, exposes StateFlows
├── WalletConfig.kt           hardcoded seed + daemon address — replace before running
└── ui/
    ├── BalanceScreen.kt      SyncState card + per-asset balance list
    ├── TransactionsScreen.kt transaction list with IN/OUT/SELF badges
    └── SendScreen.kt         address/amount/memo form, result feedback
```

`WalletConfig.DAEMON_ADDRESS = "37.27.98.156:11211"` — public Zano mainnet node.
