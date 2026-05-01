package io.horizontalsystems.zanokit

import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object KitManager {
    enum class KitState { Running, Waiting, Obsolete }

    private val lock = ReentrantLock()
    private var runningKitId: String? = null
    private var waitingKitId: String? = null

    fun checkAndGetInitialState(kitId: String): KitState = lock.withLock {
        if (runningKitId != null && runningKitId != kitId) {
            waitingKitId = kitId
            Log.e("eee", "KitState: $kitId -> Waiting (running: $runningKitId)")
            KitState.Waiting
        } else {
            runningKitId = kitId
            Log.e("eee", "KitState: $kitId -> Running")
            KitState.Running
        }
    }

    fun checkAndGetState(kitId: String): KitState = lock.withLock {
        if (runningKitId != null && runningKitId != kitId) {
            if (waitingKitId == kitId) {
                Log.e("eee", "KitState: $kitId -> still Waiting (running: $runningKitId)")
                KitState.Waiting
            } else {
                Log.e("eee", "KitState: $kitId -> Obsolete (waiting: $waitingKitId)")
                KitState.Obsolete
            }
        } else {
            runningKitId = kitId
            Log.e("eee", "KitState: $kitId -> Running (was waiting)")
            KitState.Running
        }
    }

    fun removeRunning(kitId: String) = lock.withLock {
        if (runningKitId == kitId) {
            runningKitId = null
            Log.e("eee", "KitState: $kitId -> removed from Running")
        }
    }

    fun isRunning(kitId: String): Boolean = lock.withLock { runningKitId == kitId }
}
