package com.jons.touchassist.core

/**
 * 纯 Kotlin 的目标调度状态机。
 *
 * 根据「旧目标集合 + 新目标列表 + 是否点击中」的差异，输出需要启动、取消、删除的目标集合。
 * 不依赖任何 Android 类，便于 JVM 单元测试覆盖 4 类变更分支。
 */
class TargetScheduler {

    data class Diff(
        val targetsToStart: List<AutoClickService.ClickTargetInfo>,
        val singleJobIdsToCancel: Set<String>,
        val longPressJobIdsToCancel: Set<String>,
        val removedIds: Set<String>
    )

    fun computeDiff(
        oldTargets: Map<String, AutoClickService.ClickTargetInfo>,
        newTargets: List<AutoClickService.ClickTargetInfo>,
        isClicking: Boolean
    ): Diff {
        val newIds = newTargets.map { it.id }.toSet()
        val removedIds = oldTargets.keys - newIds

        val targetsToStart = mutableListOf<AutoClickService.ClickTargetInfo>()
        val singleToCancel = mutableSetOf<String>()
        val longPressToCancel = mutableSetOf<String>()

        if (isClicking) {
            for (target in newTargets) {
                val old = oldTargets[target.id]
                when {
                    old == null -> {
                        // 新增目标 → 启动对应任务
                        targetsToStart.add(target)
                    }
                    old.clickType != target.clickType -> {
                        // 类型切换 → 取消旧类型任务，启动新类型任务
                        if (old.clickType == AutoClickService.ClickType.LONG_PRESS) {
                            longPressToCancel.add(target.id)
                        } else {
                            singleToCancel.add(target.id)
                        }
                        targetsToStart.add(target)
                    }
                    target.clickType == AutoClickService.ClickType.LONG_PRESS &&
                        (old.swipeDistance != target.swipeDistance || old.swipeAngle != target.swipeAngle) -> {
                        // 长按滑动参数变更 → 重启长按任务
                        longPressToCancel.add(target.id)
                        targetsToStart.add(target)
                    }
                }
            }
        }

        return Diff(
            targetsToStart = targetsToStart,
            singleJobIdsToCancel = singleToCancel,
            longPressJobIdsToCancel = longPressToCancel,
            removedIds = removedIds
        )
    }
}
