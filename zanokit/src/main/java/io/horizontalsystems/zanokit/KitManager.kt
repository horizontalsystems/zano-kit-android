package io.horizontalsystems.zanokit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Ensures only one ZanoKit instance runs at a time.
// A second start() call will suspend until the first kit stops.
object KitManager {
    private val mutex = Mutex()
    private var runningKitId: String? = null

    suspend fun waitAndRun(kitId: String, block: suspend () -> Unit) {
        mutex.withLock { runningKitId = kitId }
        try {
            block()
        } finally {
            mutex.withLock {
                if (runningKitId == kitId) runningKitId = null
            }
        }
    }

    fun isRunning(kitId: String): Boolean = runningKitId == kitId
}
