package io.horizontalsystems.zanokit

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object KitManager {
    enum class KitState { Running, Waiting, Obsolete }

    private val lock = ReentrantLock()
    private var runningKitId: String? = null
    private var waitingKitId: String? = null

    // Serializes the native wallet lifecycle (startCore/stopCore). It lives
    // here (global, shared by all kits) so the invariant "only one wallet is
    // opened or closed at a time" holds process-wide, matching the fact that
    // the native wallet library is a global singleton.
    // Cross-kit ORDERING during an account switch is already guaranteed by the
    // runningKitId gate below (removeRunning() runs after stopCore(), so a new
    // kit opens only after the previous one closed); the shared lock also
    // prevents a kit's own _stop from tearing down a wallet while its _start
    // is still opening it. Separate from `lock`, which only guards the
    // running/waiting bookkeeping.
    val lifecycleMutex = Mutex()

    fun checkAndGetInitialState(kitId: String): KitState = lock.withLock {
        if (runningKitId != null && runningKitId != kitId) {
            waitingKitId = kitId
            KitState.Waiting
        } else {
            runningKitId = kitId
            KitState.Running
        }
    }

    fun checkAndGetState(kitId: String): KitState = lock.withLock {
        if (runningKitId != null && runningKitId != kitId) {
            if (waitingKitId == kitId) {
                KitState.Waiting
            } else {
                KitState.Obsolete
            }
        } else {
            runningKitId = kitId
            KitState.Running
        }
    }

    fun removeRunning(kitId: String) = lock.withLock {
        if (runningKitId == kitId) {
            runningKitId = null
        }
    }

    fun isRunning(kitId: String): Boolean = lock.withLock { runningKitId == kitId }
}
