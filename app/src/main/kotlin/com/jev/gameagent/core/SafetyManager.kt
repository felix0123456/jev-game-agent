package com.jev.gameagent.core

import android.os.SystemClock
import android.util.Log

class SafetyManager {

    companion object {
        private const val TAG = "SafetyManager"
        private const val MIN_ACTION_INTERVAL_MS = 80L // Max ~12 actions/sec
        private const val MAX_CONSECUTIVE_FAILURES = 5
    }

    private var lastActionTimeMs = 0L
    private var consecutiveFailureCount = 0

    @Volatile
    var isEmergencyStopped: Boolean = false
        private set

    fun triggerEmergencyStop(reason: String) {
        isEmergencyStopped = true
        Log.w(TAG, "EMERGENCY STOP TRIGGERED: $reason")
    }

    fun reset() {
        isEmergencyStopped = false
        consecutiveFailureCount = 0
        lastActionTimeMs = 0L
    }

    fun canDispatchAction(): Boolean {
        if (isEmergencyStopped) return false

        val now = SystemClock.uptimeMillis()
        if (now - lastActionTimeMs < MIN_ACTION_INTERVAL_MS) {
            return false
        }
        lastActionTimeMs = now
        return true
    }

    fun recordSuccess() {
        consecutiveFailureCount = 0
    }

    fun recordFailure() {
        consecutiveFailureCount++
        if (consecutiveFailureCount >= MAX_CONSECUTIVE_FAILURES) {
            triggerEmergencyStop("Exceeded maximum consecutive decision failures ($MAX_CONSECUTIVE_FAILURES)")
        }
    }
}
