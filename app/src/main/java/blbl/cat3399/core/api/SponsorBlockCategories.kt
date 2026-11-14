package blbl.cat3399.core.api

import java.util.Locale

/** SponsorBlock category contract used by settings and playback filtering. */
object SponsorBlockCategories {
    const val SPONSOR = "sponsor"
    const val SELF_PROMO = "selfpromo"
    const val EXCLUSIVE_ACCESS = "exclusive_access"
    const val INTERACTION = "interaction"
    const val INTRO = "intro"
    const val OUTRO = "outro"
    const val PREVIEW = "preview"
    const val FILLER = "filler"
    const val MUSIC_OFF_TOPIC = "music_offtopic"
    const val OTHER = "clip"

    val AUTO_SKIP_KEYS: List<String> =
        listOf(
            SPONSOR,
            SELF_PROMO,
            EXCLUSIVE_ACCESS,
            INTERACTION,
            INTRO,
            OUTRO,
            PREVIEW,
            FILLER,
            MUSIC_OFF_TOPIC,
            OTHER,
        )

    private val autoSkipKeySet = AUTO_SKIP_KEYS.toSet()

    fun normalizeAutoSkipCategory(category: String?): String {
        val key = category?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            key == "padding" -> FILLER
            key in autoSkipKeySet -> key
            else -> OTHER
        }
    }

    fun normalizeSelectedAutoSkipCategories(categories: Collection<String>): List<String> {
        val selected = categories.mapTo(LinkedHashSet()) { normalizeAutoSkipCategory(it) }
        return AUTO_SKIP_KEYS.filter(selected::contains)
    }
}
