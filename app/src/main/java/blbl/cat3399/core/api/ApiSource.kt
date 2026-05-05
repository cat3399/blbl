package blbl.cat3399.core.api

enum class BiliApiSource(
    val prefValue: String,
) {
    WEB("web"),
    APP("app"),
    ;

    companion object {
        fun fromPrefValue(value: String?): BiliApiSource {
            return when (value?.trim()?.lowercase()) {
                APP.prefValue -> APP
                else -> WEB
            }
        }
    }
}

internal enum class BiliApiCapability {
    VIDEO_DETAIL,
    VIDEO_TAGS,
    VIDEO_RECOMMEND,
    VIDEO_POPULAR,
    VIDEO_REGION_LATEST,
    VIDEO_DYNAMIC_TAG,
    VIDEO_ARCHIVE_RELATED,
    VIDEO_PLAY_URL_UGC,
    VIDEO_PLAY_URL_PGC,
    VIDEO_PLAYER_INFO,
    VIDEO_ONLINE_STATUS,
    VIDEO_SHOT,
    VIDEO_UGC_SEASON_ARCHIVES,
    VIDEO_COLLECTION_SECTIONS,
    VIDEO_SERIES_ARCHIVES,
}

internal interface BiliApiSourceProvider {
    val source: BiliApiSource
    val capabilities: Set<BiliApiCapability>

    fun supports(capability: BiliApiCapability): Boolean = capability in capabilities
}

class ApiSourceUnavailableException(
    message: String,
) : IllegalStateException(message)

internal class ApiSourceSelector<T : BiliApiSourceProvider>(
    providers: List<T>,
    private val preferredSource: () -> BiliApiSource,
) {
    private val providers = providers.toList()

    fun providerFor(capability: BiliApiCapability): T {
        val capableProviders = providers.filter { it.supports(capability) }
        if (capableProviders.isEmpty()) {
            throw ApiSourceUnavailableException("api_capability_not_implemented capability=$capability")
        }

        val selected = preferredSource()
        capableProviders.firstOrNull { it.source == selected }?.let { return it }

        if (capableProviders.size == 1) {
            return capableProviders.single()
        }

        val available = capableProviders.joinToString(",") { it.source.prefValue }
        throw ApiSourceUnavailableException(
            "api_source_not_implemented capability=$capability selected=${selected.prefValue} available=$available",
        )
    }
}
