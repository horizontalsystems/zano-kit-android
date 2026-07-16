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

# Pin the Zano source to the release the current external-libs were built from
# (Zano 2.2.1.502 — the zano_native_lib pin may lag behind the release tag):
cd Zano
git fetch --tags
git checkout 2.2.1.502   # commit 76a791cc5c70f973661f07582e135fede488a66c
git submodule update --init contrib/miniupnp contrib/jwt-cpp contrib/bitcoin-secp256k1
cd ..
```

(The `contrib/tor-connect` submodule was removed upstream in 2.2.x; `bitcoin-secp256k1` and `jwt-cpp` are new.)

### Step 2 — Provide OpenSSL 3.1.8 and Boost 1.84.0 prebuilts

The repo ships prebuilt Boost/OpenSSL in `_libs_android/{boost,openssl}/{ABI}/` as **Git LFS pointers**. If `git-lfs` is installed, fetch them with:

```bash
git lfs pull --include="_libs_android/**"
```

Without LFS, build them locally with the thirdparty scripts and place the resulting `.a` files into the same layout (`_libs_android/boost/{ABI}/lib/`, `_libs_android/openssl/{ABI}/lib/`; Boost headers go to `_libs_android/boost/include/`):

```bash
export ANDROID_NDK_ROOT=$HOME/Library/Android/sdk/ndk/27.0.12077973
./thirdparty/openssl/android/build-all.sh
./thirdparty/boost/android/build.sh
```

The `VERSION` files in each `{ABI}` directory (`1.84.0` / `3.1.8`) are plain text shipped with the repo — `build/android/build.sh` reads them and fails if missing.

Boost libraries: atomic, chrono, date_time, filesystem, program_options, regex, serialization, system, thread, timer, wserialization.

**libbacktrace** (`_libs_android/libbacktrace/`) is a new optional Boost.Stacktrace backend. Our build has it disabled (directory renamed to `libbacktrace.disabled`) so Zano falls back to the basic backend — the LFS pointer files would otherwise be picked up as real archives and break the link. If enabling it, `libbacktrace.a` must also be linked into `libzanokit.so`.

### Step 3 — Apply patches

```bash
# Zano source patches:
cd ~/zano_native_lib/Zano
git am ~/StudioProjects/zano-kit-android/patches/0001-Add-generate_address-and-generate_address_from_deriv.patch
git am ~/StudioProjects/zano-kit-android/patches/0002-Increase-plain_wallet-RPC-timeout-to-20s-with-3-atte.patch

# Build-system patch (applies to zano_native_lib itself, not the Zano submodule):
cd ~/zano_native_lib
git apply ~/StudioProjects/zano-kit-android/patches/0003-zano_native_lib-android-build-file-prefix-map.patch
```

See [Patches](#patches) below for what these change.

### Step 4 — Build the Zano libraries

```bash
cd ~/zano_native_lib
export PATH="$HOME/Library/Android/sdk/cmake/3.22.1/bin:$PATH"
export ANDROID_NDK_ROOT=$HOME/Library/Android/sdk/ndk/27.0.12077973
./build/android/build.sh arm64-v8a
./build/android/build.sh armeabi-v7a
./build/android/build.sh x86_64
```

(`./build/android/build-all.sh` builds all four ABIs including `x86`, which the kit doesn't ship.) The script runs CMake with:
```
-DCMAKE_SYSTEM_NAME=Android
-DCMAKE_SYSTEM_VERSION=26          (default; override with ANDROID_TARGET env var)
-DCMAKE_ANDROID_STL_TYPE=c++_static
-DDISABLE_TOR=TRUE
-mno-unaligned-access              (armeabi-v7a only)
```

Output: `_install_android/{ABI}/lib/{libwallet,libcurrency_core,libcommon,libcrypto,libz}.a` and shared headers in `_install_android/include/`.

### Step 5 — Copy libraries and headers into the project

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

# Headers — the kit only needs these two from the install (now architecture-shared):
cp ~/zano_native_lib/_install_android/include/plain_wallet_api.h \
   ~/zano_native_lib/_install_android/include/plain_wallet_api_defs.h \
   ~/StudioProjects/zano-kit-android/zanokit/external-libs/include/
```

`external-libs/include/wallet2_api_c.h` and `zano_checksum.h` are our own files — never overwrite them. `plain_wallet_api.h` may only be copied from a **patched** source tree: the patch adds the `deinit`, `get_timestamp_from_word`, `generate_address`, and `generate_address_from_derivation` declarations the kit's C wrapper requires.

---

## Patches

All patches are in `patches/` and must be applied before building (Step 3): `0001`/`0002` to the Zano source, `0003` to the `zano_native_lib` build system. The Zano patches are regenerated against each new Zano release (`git format-patch` after committing the changes onto the release tag).

### `0001-Add-generate_address-and-generate_address_from_deriv.patch`

The kit's C wrapper (`wallet2_api_c.cpp`) needs four functions that Zano 2.2.x does not expose in the public `plain_wallet_api.h`:
- `generate_address` / `generate_address_from_derivation` — never existed upstream; the patch adds declaration + implementation.
- `get_timestamp_from_word` — existed in the plain_wallet API in 2.1.x, **removed upstream in 2.2.x**; the patch re-adds declaration + implementation (delegating to `currency::get_timestamp_from_word`, which still exists in `currency_format_utils`).
- `deinit` — implementation still exists in 2.2.x (used internally by the static-destroy handler) but its public declaration was removed; the patch re-adds only the declaration.

**Why the kit must keep calling `deinit()`** (in `ZanoWalletApi.closeWallet()`), even though upstream made it internal:
- `plain_wallet::init()` refuses to run when the global instance exists (`API_RETURN_CODE_ALREADY_EXISTS`). Without `deinit()` between kit stop and start, the next `init2()` is a silent no-op — the new daemon address and working dir are ignored, breaking wallet/node switching.
- `deinit()` (`quick_stop_no_save()`) is the only thing that stops the wallets_manager sync threads on Android. Upstream's replacement — a handler that runs during static object destruction — never fires on Android, because processes are killed, not exited; without our call the engine keeps polling the daemon after `stop()`.
- `closeWallet()` (which saves the wallet file) must run before `deinit()`, and both under `nativeLock`.
- Since `deinit` is now unofficial API, **re-check it on every Zano version bump** — if upstream deletes or repurposes it, move its body (release global instance + `quick_stop_no_save()`) into this patch as our own exported function.

**`src/currency_core/account.h`**
- Moves `void set_null()` from `private:` to `public:`. Required so the new `plain_wallet` functions can zero out the account object after extracting the address.

**`src/wallet/plain_wallet_api.h`**
- Re-declares `void deinit();`
- Declares the three other functions in the `plain_wallet` namespace:
  - `uint64_t get_timestamp_from_word(const std::string& word, bool& password_used)` — decodes the creation timestamp from a legacy seed word
  - `std::string generate_address(const std::string& seed, const std::string& seed_password)` — derives the wallet address from a 25-word legacy seed without opening a full wallet
  - `std::string generate_address_from_derivation(const std::string& secret_derivation_hex, bool is_auditable)` — derives the wallet address from a BIP39 secret derivation hex without opening a full wallet

**`src/wallet/plain_wallet_api.cpp`**
- Implements `get_timestamp_from_word`, `generate_address`, and `generate_address_from_derivation`.
- `generate_address`: constructs a `currency::account_base`, calls `restore_from_seed_phrase`, extracts the address string, then zeroes the account with `set_null()`.
- `generate_address_from_derivation`: hex-decodes the derivation key with `epee::string_tools::parse_hexstr_to_binbuff`, calls `restore_from_secret_derivation`, extracts the address, then zeroes the account.
- `get_timestamp_from_word`: delegates to `currency::get_timestamp_from_word`.

### `0002-Increase-plain_wallet-RPC-timeout-to-20s-with-3-atte.patch`

Fixes two send-time problems observed in production (Zano 2.2.1.502 update):

**`src/wallet/wallets_manager.cpp`**
- `HTTP_PROXY_TIMEOUT` 4000 → 20000, `HTTP_PROXY_ATTEMPTS_COUNT` 1 → 3. The `plain_wallet` engine configures every daemon RPC with these values; 4s/1-attempt made the send-time decoy fetch (`getrandom_outs4.bin`) fail with `no connection to daemon` on slow mobile networks. Retries are per-RPC, before signing/broadcast — no double-send risk.

**`src/wallet/wallet_rpc_server.cpp`**
- The `not_enough_money` catch handler reports `e.to_string()` instead of `e.what()` (empty for this type), so errors include `available: X, required: Y = amount + fee` — enough to tell unsynced wallet, locked change, and fee shortfall apart remotely.

### `0003-zano_native_lib-android-build-file-prefix-map.patch`

**`build/android/build.sh`** (in `zano_native_lib`, not the Zano submodule)
- Adds `-ffile-prefix-map=${PROJECT_ROOT}=.` to the compile flags so the builder's absolute filesystem paths don't leak into `__FILE__` strings (and thus wallet error messages) in the shipped binaries. Verify after building: `strings libwallet.a | grep -c "$HOME"` → 0.

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
├── KitManager.kt          ReentrantLock singleton — enforces one active kit at a time
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
- `get_recent_txs_and_info3` → `transfers[].{tx_hash, subtransfers_by_pid[].subtransfers[], employed_entries, ...}`
  (must be the v3 call: the legacy v1 returns only native-coin `amount`/`is_income` per entry and **no
  per-asset subtransfers**, which makes confidential-asset transactions invisible; v3 entries also have
  no top-level `amount`/`is_income` — the kit derives `is_income` from the native subtransfer)
- `transfer` → `{tx_hash}` — on error throws `InsufficientFundsException`, `NodeUnreachableException` (daemon RPC failed/timed out mid-send, before broadcast — retryable), or `SendFailedException`

### Transaction Logic

Zano transactions have subtransfer entries — one per asset involved (v3 nests them under
`subtransfers_by_pid[]` grouped by payment id; the kit flattens the groups):
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

### Lifecycle Threading

`ZanoKit` uses a single-threaded `lifecycleScope` (`Executors.newSingleThreadExecutor().asCoroutineDispatcher()`). Both `start()` and `stop()` dispatch work onto this executor:

```kotlin
fun start() { lifecycleScope.launch { _start() } }
fun stop()  { lifecycleScope.launch { _stop()  } }
```

`_start()` is `suspend` (uses `delay()` while waiting for `KitManager`). `_stop()` is a plain `fun`.

`ZanoCore.start()` and `ZanoCore.stop()` are also plain (non-suspend) functions. **This is intentional.** If they used `withContext(Dispatchers.IO)`, the lifecycleScope coroutine would suspend mid-execution, freeing the single thread to run `_stop()`. That would allow `_stop()` to call `KitManager.removeRunning()` while `doStart()` was still executing on a Dispatchers.IO thread — letting a second kit enter `doStart()` concurrently for the same wallet file. By keeping `start()`/`stop()` non-suspending, `doStart()` holds the lifecycleScope thread for its entire duration, and `_stop()` is guaranteed to run only after `_start()` fully completes.

### BIP39 Timestamp Mismatch Recovery

On BIP39 wallet open, `ZanoCore` compares the wallet's `creationTimestamp` against the value stored in the Room DB. If they differ, the wallet directory and DB are wiped and `start()` retries once. This handles the case where a wallet was previously synced from a different restore height. The exception type is `RestoreHeightDontMatchException`; `ZanoKit.start()` catches it, deletes `core.walletDirPath()`, calls `storage.clearAll()`, then calls `core.start()` again.

### Wallet File Layout on Device

```
filesDir/ZanoKit/{walletId}/network_{0|1}/
├── zano_core/
│   ├── wallets/
│   │   └── wallet      ← BIP39 wallet file (restore_from_derivations prepends wallets/)
│   └── wallet          ← Legacy wallet file
└── storage             ← Room database
```

---

## Zano Facts

| | |
|-|-|
| Zano source version | 2.2.1.502 (`76a791cc`) + `patches/0001`–`0002`; built with `patches/0003` |
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

`WalletConfig.DAEMON_ADDRESS = "https://zano.unstoppable.money:443"` — public Zano mainnet node.
