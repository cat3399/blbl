package blbl.cat3399.feature.video.comment

internal fun parseVideoCommentLevel(value: Any?): Int? {
    val level =
        when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    return level?.takeIf { it in VIDEO_COMMENT_LEVEL_RANGE }
}

internal fun parseVideoCommentSeniorMember(value: Any?): Boolean =
    when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> {
            val normalized = value.trim()
            when {
                normalized.equals("true", ignoreCase = true) -> true
                normalized.equals("false", ignoreCase = true) -> false
                else -> normalized.toIntOrNull()?.let { it != 0 } ?: false
            }
        }
        else -> false
    }

internal fun videoCommentLevelColor(level: Int): Int =
    when (level) {
        0, 1 -> 0xFFC0C0C0.toInt()
        2 -> 0xFF8BD29B.toInt()
        3 -> 0xFF7BCDEF.toInt()
        4 -> 0xFFFEBB8B.toInt()
        5 -> 0xFFEE672A.toInt()
        else -> 0xFFF04C49.toInt()
    }

private val VIDEO_COMMENT_LEVEL_RANGE = 0..6
