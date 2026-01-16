package blbl.cat3399.core.util

/**
 * Maps PGC access badges (bangumi/drama) to a compact UI label.
 *
 * We rely on text badges (e.g. "会员", "会员专享", "限免", "付费") because different endpoints
 * may expose different fields; callers can pass multiple raw badge strings.
 */
fun pgcAccessBadgeTextOf(vararg rawBadges: String?): String? {
    val badges = rawBadges.mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
    if (badges.isEmpty()) return null

    fun anyContains(token: String): Boolean = badges.any { it.contains(token) }

    return when {
        anyContains("大会员") || anyContains("会员") -> "大会员"
        anyContains("限免") -> "限免"
        anyContains("付费") -> "付费"
        else -> null
    }
}

