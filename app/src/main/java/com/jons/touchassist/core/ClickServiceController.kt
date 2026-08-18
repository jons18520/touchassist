package com.jons.touchassist.core

/**
 * FloatingManager 依赖的服务控制能力子集，消除 FloatingManager 对 AutoClickService 的单例强耦合。
 * AutoClickService 实现本接口，并在 onServiceConnected 将自身注入 FloatingManager。
 */
interface ClickServiceController {
    val isClicking: Boolean
    fun startClickTask()
    fun pauseClickTask()
    fun updateClickTargets(targets: List<AutoClickService.ClickTargetInfo>)
    fun stopClickService()
}
