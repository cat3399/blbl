package blbl.cat3399.core.api

import blbl.cat3399.core.model.LiveSuperChat
import org.json.JSONArray
import org.json.JSONObject

internal object LiveSuperChatJsonParser {
    internal interface ObjectValue {
        fun value(name: String): Any?

        fun objectValue(name: String): ObjectValue?
    }

    private class JsonObjectValue(
        private val source: JSONObject,
    ) : ObjectValue {
        override fun value(name: String): Any? {
            val value = source.opt(name)
            return value.takeUnless { it == null || it === JSONObject.NULL }
        }

        override fun objectValue(name: String): ObjectValue? =
            source.optJSONObject(name)?.let(::JsonObjectValue)
    }

    fun parse(source: JSONObject): LiveSuperChat? = parse(JsonObjectValue(source))

    internal fun parse(source: ObjectValue): LiveSuperChat? {
        val message =
            source.string("message")
                ?: source.string("message_jpn")
                ?: return null
        val user = source.objectValue("user_info")

        return LiveSuperChat(
            id = source.long("id") ?: 0L,
            uid = source.long("uid") ?: 0L,
            userName = user?.string("uname").orEmpty(),
            userFaceUrl = user?.string("face"),
            userNameColor = user?.string("name_color") ?: DEFAULT_USER_NAME_COLOR,
            price = (source.long("price") ?: 0L).coerceAtLeast(0L),
            message = message,
            backgroundImageUrl = source.string("background_image"),
            backgroundColor = source.string("background_color") ?: DEFAULT_BACKGROUND_COLOR,
            backgroundBottomColor =
                source.string("background_bottom_color")
                    ?: DEFAULT_BACKGROUND_BOTTOM_COLOR,
            backgroundPriceColor =
                source.string("background_price_color")
                    ?: DEFAULT_BACKGROUND_PRICE_COLOR,
            messageFontColor =
                source.string("message_font_color")
                    ?: DEFAULT_MESSAGE_FONT_COLOR,
            startTimeSeconds = source.positiveLong("start_time") ?: source.positiveLong("ts"),
            endTimeSeconds = source.positiveLong("end_time"),
            durationSeconds = source.positiveLong("time"),
        )
    }

    fun parseDeleteIds(data: JSONObject): Set<Long> {
        val ids = data.optJSONArray("ids") ?: return emptySet()
        return buildSet {
            for (index in 0 until ids.length()) {
                ids.longAt(index)?.takeIf { it > 0L }?.let(::add)
            }
        }
    }

    private fun ObjectValue.string(name: String): String? =
        value(name)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun ObjectValue.long(name: String): Long? =
        when (val value = value(name)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }

    private fun ObjectValue.positiveLong(name: String): Long? =
        long(name)?.takeIf { it > 0L }

    private fun JSONArray.longAt(index: Int): Long? =
        when (val value = opt(index)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }

    private const val DEFAULT_USER_NAME_COLOR = "#666666"
    private const val DEFAULT_BACKGROUND_COLOR = "#EDF5FF"
    private const val DEFAULT_BACKGROUND_BOTTOM_COLOR = "#2A60B2"
    private const val DEFAULT_BACKGROUND_PRICE_COLOR = "#7497CD"
    private const val DEFAULT_MESSAGE_FONT_COLOR = "#FFFFFF"
}
