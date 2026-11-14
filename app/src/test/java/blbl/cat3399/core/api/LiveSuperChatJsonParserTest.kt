package blbl.cat3399.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveSuperChatJsonParserTest {
    private class MapObjectValue(
        private val values: Map<String, Any?>,
    ) : LiveSuperChatJsonParser.ObjectValue {
        override fun value(name: String): Any? = values[name]

        override fun objectValue(name: String): LiveSuperChatJsonParser.ObjectValue? {
            @Suppress("UNCHECKED_CAST")
            val nested = values[name] as? Map<String, Any?> ?: return null
            return MapObjectValue(nested)
        }
    }

    @Test
    fun parse_should_keep_visual_and_expiry_fields() {
        val item =
            LiveSuperChatJsonParser.parse(
                MapObjectValue(
                    mapOf(
                        "id" to "6522809",
                        "uid" to 294094150L,
                        "price" to 30,
                        "message" to "测试 SC",
                        "background_image" to "https://example.com/background.png",
                        "background_color" to "#EDF5FF",
                        "background_bottom_color" to "#2A60B2",
                        "background_price_color" to "#7497CD",
                        "message_font_color" to "#A3F6FF",
                        "start_time" to 1_677_069_035L,
                        "end_time" to "1677069095",
                        "time" to 60,
                        "user_info" to
                            mapOf(
                                "uname" to "测试用户",
                                "face" to "https://example.com/avatar.jpg",
                                "name_color" to "#00D1F1",
                            ),
                    ),
                ),
            )

        requireNotNull(item)
        assertEquals(6_522_809L, item.id)
        assertEquals(294_094_150L, item.uid)
        assertEquals("测试用户", item.userName)
        assertEquals("https://example.com/avatar.jpg", item.userFaceUrl)
        assertEquals("#00D1F1", item.userNameColor)
        assertEquals(30L, item.price)
        assertEquals("测试 SC", item.message)
        assertEquals("#2A60B2", item.backgroundBottomColor)
        assertEquals(1_677_069_095L, item.endTimeSeconds)
        assertEquals(60L, item.durationSeconds)
    }

    @Test
    fun parse_should_accept_japanese_message_fallback_and_defaults() {
        val item =
            LiveSuperChatJsonParser.parse(
                MapObjectValue(
                    mapOf(
                        "id" to 7L,
                        "message_jpn" to "翻译文本",
                        "user_info" to mapOf("uname" to "用户"),
                    ),
                ),
            )

        requireNotNull(item)
        assertEquals("翻译文本", item.message)
        assertEquals("#EDF5FF", item.backgroundColor)
        assertEquals("#2A60B2", item.backgroundBottomColor)
        assertEquals("#FFFFFF", item.messageFontColor)
    }

    @Test
    fun parse_should_reject_payload_without_message() {
        val item =
            LiveSuperChatJsonParser.parse(
                MapObjectValue(
                    mapOf(
                        "id" to 7L,
                        "user_info" to mapOf("uname" to "用户"),
                    ),
                ),
            )

        assertNull(item)
    }
}
