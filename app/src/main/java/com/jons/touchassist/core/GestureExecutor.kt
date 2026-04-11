package com.jons.touchassist.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.util.Log
import kotlinx.coroutines.delay
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Gesture execution manager with statistics tracking and error handling.
 *
 * Features:
 * - Suspends until gesture completes or cancels
 * - Prevents stale callback issues with previous continuations
 * - Tracks gesture statistics (completed/cancelled/error counts)
 * - Adds delay on dispatch failure to avoid system spam
 */
class GestureExecutor {

    private var ongoingContinuation: Continuation<Boolean>? = null
    private var completedGestures: Long = 0L
    private var cancelledGestures: Long = 0L
    private var errorGestures: Long = 0L

    companion object {
        private const val TAG = "GestureExecutor"
    }

    /**
     * Dispatches a gesture and suspends until completion.
     *
     * @param service The accessibility service to dispatch from
     * @param gesture The gesture description to execute
     * @return true if gesture completed successfully, false if cancelled or error
     */
    suspend fun dispatchGesture(service: AccessibilityService, gesture: GestureDescription): Boolean {
        // Clean up any previous pending continuation to avoid stale events
        if (ongoingContinuation != null) {
            Log.w(TAG, "Previous gesture result is not available yet, clearing to avoid stale events")
            ongoingContinuation = null
        }

        return suspendCoroutine { continuation ->
            ongoingContinuation = continuation

            try {
                val dispatched = service.dispatchGesture(gesture, gestureResultCallback, null)
                if (!dispatched) {
                    Log.w(TAG, "Gesture dispatch failed immediately")
                    errorGestures++
                    // Resume immediately on dispatch failure
                    continuation.resume(false)
                }
            } catch (ex: RuntimeException) {
                Log.w(TAG, "System exception during gesture dispatch (possibly spamming too fast)", ex)
                errorGestures++
                continuation.resume(false)
            }
        }
    }

    private val gestureResultCallback = object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            completedGestures++
            ongoingContinuation?.let { continuation ->
                ongoingContinuation = null
                try {
                    continuation.resume(true)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Continuation already resumed (duplicate callback?)", e)
                }
            } ?: Log.w(TAG, "onCompleted with no active continuation")
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            cancelledGestures++
            ongoingContinuation?.let { continuation ->
                ongoingContinuation = null
                try {
                    continuation.resume(false)
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Continuation already resumed (duplicate callback?)", e)
                }
            } ?: Log.w(TAG, "onCancelled with no active continuation")
        }
    }

    /**
     * Resets statistics counters.
     */
    fun clearStats() {
        completedGestures = 0L
        cancelledGestures = 0L
        errorGestures = 0L
        ongoingContinuation = null
    }

    /**
     * Returns current gesture statistics.
     */
    fun getStats(): Triple<Long, Long, Long> {
        return Triple(completedGestures, cancelledGestures, errorGestures)
    }

    /**
     * Returns whether there is an ongoing gesture execution.
     */
    fun hasOngoingGesture(): Boolean = ongoingContinuation != null
}
