package com.jons.touchassist.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureExecutorTest {

    private class FakeDispatcher : GestureDispatcher {
        var onDispatch: ((GestureDescription?, AccessibilityService.GestureResultCallback, Handler?) -> Boolean)? = null

        override fun dispatch(
            gesture: GestureDescription?,
            callback: AccessibilityService.GestureResultCallback,
            handler: Handler?
        ): Boolean = onDispatch?.invoke(gesture, callback, handler) ?: false
    }

    @Test
    fun completedResumesTrue() = runBlocking {
        val executor = GestureExecutor()
        val fake = FakeDispatcher().apply {
            onDispatch = { _, callback, _ ->
                callback.onCompleted(null)
                true
            }
        }

        val result = executor.dispatchGesture(fake, null)

        assertTrue(result)
        assertFalse(executor.hasOngoingGesture())
        val (completed, cancelled, errors) = executor.getStats()
        assertEquals(1L, completed)
        assertEquals(0L, cancelled)
        assertEquals(0L, errors)
    }

    @Test
    fun cancelledResumesFalse() = runBlocking {
        val executor = GestureExecutor()
        val fake = FakeDispatcher().apply {
            onDispatch = { _, callback, _ ->
                callback.onCancelled(null)
                true
            }
        }

        val result = executor.dispatchGesture(fake, null)

        assertFalse(result)
        assertFalse(executor.hasOngoingGesture())
        val (completed, cancelled, errors) = executor.getStats()
        assertEquals(0L, completed)
        assertEquals(1L, cancelled)
        assertEquals(0L, errors)
    }

    @Test
    fun immediateDispatchFailureResumesFalseAndClearsContinuation() = runBlocking {
        val executor = GestureExecutor()
        val fake = FakeDispatcher().apply {
            onDispatch = { _, _, _ -> false }
        }

        val result = executor.dispatchGesture(fake, null)

        assertFalse(result)
        assertFalse(executor.hasOngoingGesture())
        val (_, _, errors) = executor.getStats()
        assertEquals(1L, errors)
    }

    @Test
    fun dispatchExceptionResumesFalseAndClearsContinuation() = runBlocking {
        val executor = GestureExecutor()
        val fake = FakeDispatcher().apply {
            onDispatch = { _, _, _ -> throw RuntimeException("boom") }
        }

        val result = executor.dispatchGesture(fake, null)

        assertFalse(result)
        assertFalse(executor.hasOngoingGesture())
    }

    @Test
    fun duplicateCallbackDoesNotCrash() = runBlocking {
        val executor = GestureExecutor()
        val fake = FakeDispatcher().apply {
            onDispatch = { _, callback, _ ->
                callback.onCompleted(null)
                callback.onCompleted(null) // duplicate, should be ignored
                true
            }
        }

        val result = executor.dispatchGesture(fake, null)

        assertTrue(result)
        assertFalse(executor.hasOngoingGesture())
    }

    @Test
    fun callbackAfterCancellationIsSafe() = runBlocking {
        val executor = GestureExecutor()
        val capturedCallback = CompletableDeferred<AccessibilityService.GestureResultCallback>()
        val fake = FakeDispatcher().apply {
            onDispatch = { _, callback, _ ->
                capturedCallback.complete(callback)
                true // dispatched, callback will arrive later
            }
        }

        val job = launch { executor.dispatchGesture(fake, null) }
        val callback = capturedCallback.await()
        job.cancel()
        job.join()

        // 协程已取消，continuation 已被 invokeOnCancellation 清空，此时回调到达应安全
        assertFalse(executor.hasOngoingGesture())
        callback.onCompleted(null)
        assertFalse(executor.hasOngoingGesture())
    }
}
