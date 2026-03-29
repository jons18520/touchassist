package com.jons.touchassist.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class AutoClickService : AccessibilityService() {

    companion object {
        const val ACTION_SHOW_OVERLAYS = "com.jons.touchassist.action.SHOW_OVERLAYS"
        private const val LONG_PRESS_DURATION = 400L  // 一次按下+滑动的时长(ms)
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

    private val activeLongPressRunnables = ConcurrentHashMap<String, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    val isClicking: Boolean
        get() = isClickingInternal.get()

    private val isClickingInternal = AtomicBoolean(false)
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
        Log.w("TouchService", "Accessibility service interrupted")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopAllTasks()
        FloatingManager.removeAllViews()
    }

    @Synchronized
    fun startClickTask() {
        if (!isClickingInternal.compareAndSet(false, true)) {
            return
        }

        FloatingManager.syncTargetsToService()
        FloatingManager.setClickingState(true)
        FloatingManager.setTargetPointTouchable(false)
        FloatingManager.updateControlPanelState(true)

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
        singleClickJobs[targetId] = serviceScope.launch {
            try {
                while (isActive && isClickingInternal.get()) {
                    val target = clickTargetsById[targetId] ?: break
                    if (target.clickType != ClickType.SINGLE) break
                    performSingleClick(target)
                    delay(jitterDelay(target.interval))
                }
            } catch (_: CancellationException) {
            }
            singleClickJobs.remove(targetId)
        }
    }

    private fun startOrRestartLongPressTask(targetId: String) {
        activeLongPressRunnables.remove(targetId)?.let { handler.removeCallbacks(it) }

        val target = clickTargetsById[targetId] ?: return
        if (target.clickType != ClickType.LONG_PRESS) {
            return
        }

        val swipeDistancePx = target.swipeDistance.coerceAtLeast(0)

        lateinit var runnable: Runnable
        runnable = Runnable {
            if (!isClickingInternal.get()) return@Runnable

            val latestTarget = clickTargetsById[targetId] ?: return@Runnable
            if (latestTarget.clickType != ClickType.LONG_PRESS) {
                activeLongPressRunnables.remove(targetId)
                return@Runnable
            }

            val path = Path()
            val startX = jitter(latestTarget.x)
            val startY = jitter(latestTarget.y)

            if (swipeDistancePx > 0) {
                val angleRad = Math.toRadians(latestTarget.swipeAngle.toDouble())
                val dx = (kotlin.math.cos(angleRad) * swipeDistancePx).toInt()
                val dy = (kotlin.math.sin(angleRad) * swipeDistancePx).toInt()
                path.moveTo(startX, startY)
                path.lineTo(jitter(startX + dx, 2), jitter(startY + dy, 2))
            } else {
                path.moveTo(startX, startY)
            }

            Log.d(
                "TouchService",
                "Long press $targetId swipe=${latestTarget.swipeDistance}px angle=${latestTarget.swipeAngle}°"
            )

            val duration = jitterDuration(LONG_PRESS_DURATION, 100L)
            val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
            val gestureDescription = GestureDescription.Builder()
                .addStroke(strokeDescription)
                .build()

            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (isClickingInternal.get() && clickTargetsById[targetId]?.clickType == ClickType.LONG_PRESS) {
                        handler.postDelayed(runnable, jitterDelay(50L, 0.5))
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    activeLongPressRunnables.remove(targetId)
                }
            }

            val dispatched = dispatchGesture(gestureDescription, callback, handler)
            if (!dispatched) {
                Log.w("TouchService", "Long press $targetId dispatch failed")
                handler.postDelayed(runnable, 100)
            }
        }

        activeLongPressRunnables[targetId] = runnable
        handler.postDelayed(runnable, 100)
    }

    private suspend fun performSingleClick(target: ClickTargetInfo): Boolean = suspendCoroutine { continuation ->
        val x = jitter(target.x)
        val y = jitter(target.y)
        Log.d("TouchService", "Clicking target ${target.id} at ($x, $y) interval=${target.interval}")

        val path = Path().apply {
            moveTo(x, y)
            lineTo(jitter(x, 1), jitter(y, 1))
        }

        val duration = jitterDuration(50L, 40L)
        val strokeDescription = GestureDescription.StrokeDescription(path, 0, duration)
        val gestureDescription = GestureDescription.Builder()
            .addStroke(strokeDescription)
            .build()

        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                continuation.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w("TouchService", "Single click ${target.id} cancelled")
                continuation.resume(false)
            }
        }

        val dispatched = dispatchGesture(gestureDescription, callback, null)
        if (!dispatched) {
            Log.w("TouchService", "Single click ${target.id} rejected")
            continuation.resume(false)
        }
    }

    private fun stopAllTasks() {
        singleClickJobs.values.forEach { it.cancel() }
        singleClickJobs.clear()

        activeLongPressRunnables.values.forEach { handler.removeCallbacks(it) }
        activeLongPressRunnables.clear()

        // 发送极短空手势以中断当前正在执行的长按手势
        try {
            val path = Path().apply { moveTo(0f, 0f) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 1L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {}
    }

    fun pauseClickTask() {
        stopAllTasks()
        isClickingInternal.set(false)
        FloatingManager.setClickingState(false)
        FloatingManager.setTargetPointTouchable(true)
        FloatingManager.updateControlPanelState(false)
    }

    fun stopClickTask() {
        pauseClickTask()
    }

    fun stopClickService() {
        pauseClickTask()
        shouldShowOverlays = false
        FloatingManager.hideAllViews()
        stopSelf()
    }

    fun updateSettings(interval: Long) {
        clickTargetsById.entries.forEach { (id, target) ->
            clickTargetsById[id] = target.copy(interval = interval)
        }
    }

    fun updateClickTargets(targets: List<ClickTargetInfo>) {
        val newIds = targets.map { it.id }.toSet()
        val oldIds = clickTargetsById.keys.toSet()

        targets.forEach { target ->
            val oldTarget = clickTargetsById[target.id]
            clickTargetsById[target.id] = target

            if (isClickingInternal.get()) {
                if (oldTarget == null) {
                    // 新增目标，立即启动
                    if (target.clickType == ClickType.SINGLE) {
                        startSingleClickJob(target.id)
                    } else {
                        startOrRestartLongPressTask(target.id)
                    }
                } else if (oldTarget.clickType != target.clickType) {
                    // 类型变化，重启
                    if (oldTarget.clickType == ClickType.LONG_PRESS) {
                        activeLongPressRunnables.remove(target.id)?.let { handler.removeCallbacks(it) }
                    } else {
                        singleClickJobs.remove(target.id)?.cancel()
                    }
                    if (target.clickType == ClickType.LONG_PRESS) {
                        startOrRestartLongPressTask(target.id)
                    } else {
                        startSingleClickJob(target.id)
                    }
                } else if (target.clickType == ClickType.LONG_PRESS &&
                    (oldTarget.swipeDistance != target.swipeDistance || oldTarget.swipeAngle != target.swipeAngle)) {
                    startOrRestartLongPressTask(target.id)
                }
            }
        }

        val removedIds = oldIds - newIds
        removedIds.forEach { id ->
            clickTargetsById.remove(id)
            singleClickJobs.remove(id)?.cancel()
            activeLongPressRunnables.remove(id)?.let { handler.removeCallbacks(it) }
        }

        Log.d("TouchService", "Updated ${targets.size} targets")
    }

    fun updateTargetPosition(x: Float, y: Float) {
        val firstId = clickTargetsById.keys.firstOrNull() ?: return
        val target = clickTargetsById[firstId] ?: return
        clickTargetsById[firstId] = target.copy(x = x, y = y)
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
