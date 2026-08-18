package com.jons.touchassist.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler

/**
 * 抽象无障碍手势派发能力，隔离 GestureExecutor 对 AccessibilityService 的直接依赖，
 * 便于单元测试注入 fake 实现。
 *
 * @param gesture 手势描述。生产环境恒非空；单元测试的 fake 实现可传 null。
 */
interface GestureDispatcher {
    fun dispatch(
        gesture: GestureDescription?,
        callback: AccessibilityService.GestureResultCallback,
        handler: Handler?
    ): Boolean
}
