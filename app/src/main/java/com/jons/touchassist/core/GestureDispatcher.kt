package com.jons.touchassist.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler

/**
 * 抽象无障碍手势派发能力，隔离 GestureExecutor 对 AccessibilityService 的直接依赖，
 * 便于单元测试注入 fake 实现。
 */
interface GestureDispatcher {
    fun dispatch(
        gesture: GestureDescription,
        callback: AccessibilityService.GestureResultCallback,
        handler: Handler?
    ): Boolean
}
