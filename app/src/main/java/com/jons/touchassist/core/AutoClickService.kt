package com.jons.touchassist.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class AutoClickService : AccessibilityService(), GestureDispatcher {

    companion object {
        const val ACTION_SHOW_OVERLAYS = "com.jons.touchassist.action.SHOW_OVERLAYS"
        private const val LONG_PRESS_DURATION = 400L
        private const val TAG = "TouchService"
    }

    data class ClickTargetInfo(
        val id: String,
        val x: Float,
        val y: Float,
        val clickType: ClickType = ClickType.SINGLE,
        val interval: Long = 100L,
        val swipeDistance: Int = 0,
        val swipeAngle: Int = 270
    )

    enum class ClickType { SINGLE, LONG_PRESS }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val clickTargetsById = ConcurrentHashMap<String, ClickTargetInfo>()
    private val singleClickJobs = ConcurrentHashMap<String, Job>()
    private val longPressJobs = ConcurrentHashMap<String, Job>()

    // 每个目标独立的 GestureExecutor，避免并发冲突
    private val gestureExecutors = ConcurrentHashMap<String, GestureExecutor>()

    // 目标调度状态机（纯 Kotlin，可单测）
    private val scheduler = TargetScheduler()

    // StateFlow for click state - replaces AtomicBoolean
    private val _isClicking = MutableStateFlow(false)
    val isClickingState: StateFlow<Boolean> = _isClicking.asStateFlow()
    val isClicking: Boolean
        get() = _isClicking.value
    private var shouldShowOverlays = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        FloatingManager.init(this)
        showOverlaysIfRequested()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_OVERLAYS) {
            shouldShowOverlays = true
            showOverlaysIfRequested()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onInterrupt() {
        pauseClickTask()
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun dispatch(
        gesture: GestureDescription?,
        callback: AccessibilityService.GestureResultCallback,
        handler: Handler?
    ): Boolean = dispatchGesture(requireNotNull(gesture), callback, handler)

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopAllTasks()
        FloatingManager.removeAllViews()
    }

    @Synchronized
    fun startClickTask() {
        if (_isClicking.value) {
            return
        }

        FloatingManager.syncTargetsToService()
        FloatingManager.setClickingState(true)
        FloatingManager.setTargetPointTouchable(false)
        FloatingManager.updateControlPanelState(true)

        _isClicking.value = true

        clickTargetsById.values.forEach { target ->
            if (target.clickType == ClickType.SINGLE) {
                startSingleClickJob(target.id)
            } else {
                startOrRestartLongPressTask(target.id)
            }
        }
    }

    private fun startSingleClickJob(targetId: String) {
        singleClickJobs[targetId]?.cancel()
        // 为每个目标创建独立的 GestureExecutor
        val executor = GestureExecutor()
        gestureExecutors[targetId] = executor

        val job = serviceScope.launch {
            try {
                while (isActive && _isClicking.value) {
                    val target = clickTargetsById[targetId] ?: break
                    if (target.clickType != ClickType.SINGLE) break

                    performSingleClick(target, executor)

                    if (!isActive || !_isClicking.value) break

                    delay(jitterDelay(target.interval))
                }
            } catch (_: CancellationException) {
                // 预期的取消
            }
            singleClickJobs.remove(targetId)
            gestureExecutors.remove(targetId)
        }
        singleClickJobs[targetId] = job
    }

    private suspend fun performSingleClick(target: ClickTargetInfo, executor: GestureExecutor): Boolean {
        val x = jitter(target.x)
        val y = jitter(target.y)

        val path = Path().apply {
            moveTo(x, y)
            lineTo(jitter(x, 1), jitter(y, 1))
        }

        val duration = jitterDuration(50L, 40L)
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
        val gestureDescription = GestureDescription.Builder()
            .addStroke(strokeDescription)
            .build()

        val success = executor.dispatchGesture(this, gestureDescription)

        if (!success) {
            Log.w(TAG, "Single click failed for target ${target.id}, delaying to avoid spam")
            delay(500)  // Protection against spamming gestures too quickly
        }

        return success
    }

    private fun startOrRestartLongPressTask(targetId: String) {
        // 取消现有的长按任务 Job
        longPressJobs[targetId]?.cancel()
        longPressJobs.remove(targetId)

        val target = clickTargetsById[targetId] ?: return
        if (target.clickType != ClickType.LONG_PRESS) {
            return
        }

        // 为每个目标创建独立的 GestureExecutor
        val executor = GestureExecutor()
        gestureExecutors[targetId] = executor

        val job = serviceScope.launch {
            try {
                while (isActive && _isClicking.value) {
                    val currentTarget = clickTargetsById[targetId] ?: break
                    if (currentTarget.clickType != ClickType.LONG_PRESS) break

                    performLongPress(targetId, currentTarget, executor)

                    if (!isActive || !_isClicking.value) break

                    delay(jitterDelay(50L, 0.5))
                }
            } catch (_: CancellationException) {
                // 预期的取消
            }
            longPressJobs.remove(targetId)
            gestureExecutors.remove(targetId)
        }
        longPressJobs[targetId] = job
    }

    private suspend fun performLongPress(targetId: String, target: ClickTargetInfo, executor: GestureExecutor) {
        val swipeDistancePx = target.swipeDistance.coerceAtLeast(0)

        val path = Path().apply {
            val startX = jitter(target.x)
            val startY = jitter(target.y)
            moveTo(startX, startY)

            if (swipeDistancePx > 0) {
                val angleRad = Math.toRadians(target.swipeAngle.toDouble())
                val dx = (kotlin.math.cos(angleRad) * swipeDistancePx).toInt()
                val dy = (kotlin.math.sin(angleRad) * swipeDistancePx).toInt()
                lineTo(jitter(startX + dx, 2), jitter(startY + dy, 2))
            }
        }

        val duration = jitterDuration(LONG_PRESS_DURATION, 100L)
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
        val gestureDescription = GestureDescription.Builder()
            .addStroke(strokeDescription)
            .build()

        val success = executor.dispatchGesture(this, gestureDescription)

        if (!success) {
            Log.w(TAG, "Long press failed for target $targetId, delaying to avoid spam")
            delay(500)  // Protection against spamming gestures too quickly
        }
    }

    private fun stopAllTasks() {
        Log.w(TAG, "stopAllTasks() called")
        Log.w(TAG, "singleClickJobs: ${singleClickJobs.size}, longPressJobs: ${longPressJobs.size}")

        // 取消所有单击任务
        singleClickJobs.values.forEach { job ->
            Log.d(TAG, "Cancelling single click job: isActive=${job.isActive}")
            job.cancel()
        }
        singleClickJobs.clear()

        // 取消所有长按任务
        longPressJobs.values.forEach { job ->
            Log.d(TAG, "Cancelling long press job: isActive=${job.isActive}")
            job.cancel()
        }
        longPressJobs.clear()
    }

    fun pauseClickTask() {
        Log.w(TAG, "=== pauseClickTask() called ===")
        _isClicking.value = false
        Log.w(TAG, "_isClicking.value = false")

        stopAllTasks()

        FloatingManager.setClickingState(false)
        FloatingManager.setTargetPointTouchable(true)
        FloatingManager.updateControlPanelState(false)
        Log.w(TAG, "=== pauseClickTask() completed ===")
    }

    fun stopClickService() {
        pauseClickTask()
        shouldShowOverlays = false
        FloatingManager.hideAllViews()
        stopSelf()
    }

    fun updateClickTargets(targets: List<ClickTargetInfo>) {
        val diff = scheduler.computeDiff(clickTargetsById.toMap(), targets, _isClicking.value)

        // 1. 更新目标表
        targets.forEach { clickTargetsById[it.id] = it }

        // 2. 移除已删除目标
        diff.removedIds.forEach { id ->
            clickTargetsById.remove(id)
            singleClickJobs.remove(id)?.cancel()
            longPressJobs.remove(id)?.cancel()
        }

        // 3. 取消需要停掉的旧任务
        diff.singleJobIdsToCancel.forEach { singleClickJobs.remove(it)?.cancel() }
        diff.longPressJobIdsToCancel.forEach { longPressJobs.remove(it)?.cancel() }

        // 4. 启动/重启目标
        diff.targetsToStart.forEach { target ->
            if (target.clickType == ClickType.SINGLE) {
                startSingleClickJob(target.id)
            } else {
                startOrRestartLongPressTask(target.id)
            }
        }

        Log.d(TAG, "Updated ${targets.size} targets")
    }

    private fun showOverlaysIfRequested() {
        if (!shouldShowOverlays) {
            return
        }

        FloatingManager.showControlPanel()
        FloatingManager.restorePersistedSettings()
    }

    private fun jitter(value: Float, radius: Int = 3): Float {
        return value + (Math.random() * radius * 2 - radius).toFloat()
    }

    private fun jitterDelay(base: Long, ratio: Double = 0.1): Long {
        val delta = (base * ratio * (Math.random() * 2 - 1)).toLong()
        return (base + delta).coerceAtLeast(20L)
    }

    private fun jitterDuration(base: Long, rangeMs: Long = 40L): Long {
        return base + (Math.random() * rangeMs).toLong()
    }
}
