package com.jons.touchassist.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 全局触控参数：所有目标共用的间隔与滑动配置，由悬浮面板设置按钮统一配置。
 * 触控类型（单次/持续）不属于全局设置，是每个目标自己的属性（编辑模式下点按目标切换）。
 */
data class GlobalSettings(
    val interval: Long = 100L,
    val swipeDistance: Int = 0,
    val swipeAngle: Int = 270
)

/** 方案中的单个触控目标快照：位置 + 触控类型（视图左上角坐标，与 click_targets 持久化口径一致） */
data class ProfileTarget(
    val x: Float,
    val y: Float,
    val clickType: AutoClickService.ClickType = AutoClickService.ClickType.SINGLE
)

/**
 * 配置方案：全局参数 + 触控目标（数量/位置/类型）的快照，用于一键切换整套配置。
 */
data class ConfigProfile(
    val id: String,
    val name: String,
    val settings: GlobalSettings,
    val targets: List<ProfileTarget>
)

/**
 * 方案与全局参数的 JSON 编解码（纯 Kotlin + org.json）。
 * 解析失败返回 null，由调用方决定回退行为，不抛异常；
 * clickType 宽松解析（未知/缺失回退 SINGLE），其余字段缺失视为坏数据整体失败。
 */
object ConfigProfileCodec {

    fun settingsToJson(settings: GlobalSettings): String = settingsToJsonObject(settings).toString()

    fun settingsFromJson(json: String): GlobalSettings? = try {
        parseSettings(JSONObject(json))
    } catch (_: Exception) {
        null
    }

    fun profilesToJson(profiles: List<ConfigProfile>): String {
        val array = JSONArray()
        profiles.forEach { profile ->
            val obj = JSONObject()
            obj.put("id", profile.id)
            obj.put("name", profile.name)
            obj.put("settings", settingsToJsonObject(profile.settings))
            val targets = JSONArray()
            profile.targets.forEach { target ->
                targets.put(targetToJsonObject(target))
            }
            obj.put("targets", targets)
            array.put(obj)
        }
        return array.toString()
    }

    fun profilesFromJson(json: String): List<ConfigProfile>? = try {
        val array = JSONArray(json)
        val result = mutableListOf<ConfigProfile>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val targets = mutableListOf<ProfileTarget>()
            val targetsArray = obj.getJSONArray("targets")
            for (j in 0 until targetsArray.length()) {
                targets.add(parseTarget(targetsArray.getJSONObject(j)))
            }
            result.add(
                ConfigProfile(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    settings = parseSettings(obj.getJSONObject("settings")),
                    targets = targets
                )
            )
        }
        result
    } catch (_: Exception) {
        null
    }

    private fun parseTarget(obj: JSONObject): ProfileTarget = ProfileTarget(
        x = obj.getDouble("x").toFloat(),
        y = obj.getDouble("y").toFloat(),
        clickType = clickTypeFromRaw(obj.optString("clickType"))
    )

    private fun parseSettings(obj: JSONObject): GlobalSettings = GlobalSettings(
        interval = obj.getLong("interval"),
        swipeDistance = obj.getInt("swipeDistance"),
        swipeAngle = obj.getInt("swipeAngle")
    )

    private fun parseClickType(obj: JSONObject): AutoClickService.ClickType =
        clickTypeFromRaw(obj.optString("clickType"))

    /** 宽松解析触控类型：未知/缺失值回退 SINGLE */
    fun clickTypeFromRaw(raw: String?): AutoClickService.ClickType =
        try { AutoClickService.ClickType.valueOf(raw ?: "") } catch (_: Exception) { AutoClickService.ClickType.SINGLE }

    private fun targetToJsonObject(target: ProfileTarget): JSONObject {
        val obj = JSONObject()
        obj.put("x", target.x.toDouble())
        obj.put("y", target.y.toDouble())
        obj.put("clickType", target.clickType.name)
        return obj
    }

    private fun settingsToJsonObject(settings: GlobalSettings): JSONObject {
        val obj = JSONObject()
        obj.put("interval", settings.interval)
        obj.put("swipeDistance", settings.swipeDistance)
        obj.put("swipeAngle", settings.swipeAngle)
        return obj
    }
}
