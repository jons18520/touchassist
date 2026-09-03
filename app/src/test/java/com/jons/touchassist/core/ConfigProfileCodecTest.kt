package com.jons.touchassist.core

import com.jons.touchassist.core.AutoClickService.ClickType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigProfileCodecTest {

    @Test
    fun settingsRoundTrip() {
        val settings = GlobalSettings(interval = 200L, swipeDistance = 80, swipeAngle = 90)

        val restored = ConfigProfileCodec.settingsFromJson(ConfigProfileCodec.settingsToJson(settings))

        assertEquals(settings, restored)
    }

    @Test
    fun settingsFromJsonInvalidReturnsNull() {
        assertNull(ConfigProfileCodec.settingsFromJson("not json"))
        assertNull(ConfigProfileCodec.settingsFromJson("{}"))
    }

    @Test
    fun settingsFromJsonIgnoresLegacyClickTypeField() {
        // 旧版本全局设置对象可能带 clickType 字段，解析应忽略未知字段
        val restored = ConfigProfileCodec.settingsFromJson(
            """{"clickType":"LONG_PRESS","interval":150,"swipeDistance":10,"swipeAngle":180}"""
        )

        assertEquals(GlobalSettings(interval = 150L, swipeDistance = 10, swipeAngle = 180), restored)
    }

    @Test
    fun profilesRoundTrip() {
        val profiles = listOf(
            ConfigProfile(
                id = "p1",
                name = "游戏连点",
                settings = GlobalSettings(interval = 50L),
                targets = listOf(
                    ProfileTarget(100.5f, 200.25f, ClickType.SINGLE),
                    ProfileTarget(300f, 400f, ClickType.LONG_PRESS)
                )
            ),
            ConfigProfile(
                id = "p2",
                name = "滑动方案",
                settings = GlobalSettings(swipeDistance = 120, swipeAngle = 270),
                targets = emptyList()
            )
        )

        val restored = ConfigProfileCodec.profilesFromJson(ConfigProfileCodec.profilesToJson(profiles))

        assertEquals(profiles, restored)
    }

    @Test
    fun profilesFromJsonInvalidReturnsNull() {
        assertNull(ConfigProfileCodec.profilesFromJson("not json"))
        assertNull(ConfigProfileCodec.profilesFromJson("["))
        // 缺少 name/settings/targets 等必需键
        assertNull(ConfigProfileCodec.profilesFromJson("""[{"id":"p1"}]"""))
    }

    @Test
    fun profilesFromJsonLenientTargetClickType() {
        // 目标缺少/携带未知 clickType 时回退 SINGLE
        val restored = ConfigProfileCodec.profilesFromJson(
            """[{"id":"p1","name":"n","settings":{"interval":1,"swipeDistance":0,"swipeAngle":270},"targets":[
               {"x":1,"y":2},{"x":3,"y":4,"clickType":"WHAT"},{"x":5,"y":6,"clickType":"LONG_PRESS"}]}]""".replace("\n", "")
        )

        assertEquals(
            listOf(
                ProfileTarget(1f, 2f, ClickType.SINGLE),
                ProfileTarget(3f, 4f, ClickType.SINGLE),
                ProfileTarget(5f, 6f, ClickType.LONG_PRESS)
            ),
            restored?.first()?.targets
        )
    }
}
