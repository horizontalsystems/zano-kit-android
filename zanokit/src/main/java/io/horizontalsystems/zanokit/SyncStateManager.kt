package io.horizontalsystems.zanokit

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SyncStateManager(
    private val api: ZanoWalletApi,
    private val restoreHeight: Long,
) {
    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val STORE_BLOCKS_COUNT = 2_000L
    }

    private val _syncState = MutableStateFlow<SyncState>(SyncState.NotSynced.NotStarted)
    val syncStateFlow: StateFlow<SyncState> = _syncState

    var onSyncedPoll: (() -> Unit)? = null

    private var job: Job? = null
    private var connectStartTime = 0L

    private var isNetworkAvailable = true
    private var isDaemonConnected = false
    val isInLongRefresh get() = _isInLongRefresh
    private var _isInLongRefresh = false
    private var walletHeight = 0L
    private var daemonHeight = 0L
    private var lastRefreshedHeight = 0L
    private var lastStoredBlockHeight = 0L

    val chunkOfBlocksSynced: Boolean
        get() {
            if (lastStoredBlockHeight < restoreHeight) return false
            return lastStoredBlockHeight <= walletHeight &&
                walletHeight - lastStoredBlockHeight >= STORE_BLOCKS_COUNT
        }

    fun walletStored() {
        lastStoredBlockHeight = walletHeight
    }

    fun onNetworkAvailabilityChange(available: Boolean) {
        isNetworkAvailable = available
        checkSyncState()
    }

    fun start(scope: CoroutineScope) {
        connectStartTime = System.currentTimeMillis()
        _syncState.value = SyncState.Connecting(waiting = false)
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                checkSyncState()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _syncState.value = SyncState.NotSynced.NotStarted
        walletHeight = 0L
        daemonHeight = 0L
        lastRefreshedHeight = 0L
        lastStoredBlockHeight = 0L
        isDaemonConnected = false
        _isInLongRefresh = false
        isNetworkAvailable = true
    }

    private fun checkSyncState() {
        val status = api.getWalletStatus()

        if (status != null) {
            walletHeight = status.optLong("current_wallet_height", 0)
            daemonHeight = status.optLong("current_daemon_height", 0)
            isDaemonConnected = status.optBoolean("is_daemon_connected", false)
            _isInLongRefresh = status.optBoolean("is_in_long_refresh", false)
            if (lastStoredBlockHeight < restoreHeight) lastStoredBlockHeight = walletHeight
        }

        val newState = evaluateState()
        _syncState.value = newState

        if (newState is SyncState.Synced && walletHeight > lastRefreshedHeight) {
            lastRefreshedHeight = walletHeight
            onSyncedPoll?.invoke()
        }
    }

    private fun evaluateState(): SyncState {
        if (!isNetworkAvailable) return SyncState.NotSynced.NoNetwork
        if (!isDaemonConnected) {
            val elapsed = System.currentTimeMillis() - connectStartTime
            if (elapsed > CONNECT_TIMEOUT_MS) {
                return SyncState.NotSynced.StatusError("Connection timed out")
            }
            return SyncState.Connecting(waiting = false)
        }

        if (daemonHeight == 0L) return SyncState.Connecting(waiting = false)

        if (walletHeight + 2 >= daemonHeight && !_isInLongRefresh) return SyncState.Synced

        val effectiveRestoreHeight = minOf(restoreHeight, daemonHeight)
        val blocksToSync = (daemonHeight - effectiveRestoreHeight).coerceAtLeast(1L)
        val blocksSynced = (walletHeight - effectiveRestoreHeight).coerceAtLeast(0L)

        if (blocksToSync <= 0) return SyncState.Synced

        val progress = (blocksSynced * 100 / blocksToSync).coerceIn(0, 100).toInt()
        val remaining = (blocksToSync - blocksSynced).coerceAtLeast(0L)
        return SyncState.Syncing(progress = progress, remainingBlocks = remaining)
    }
}
