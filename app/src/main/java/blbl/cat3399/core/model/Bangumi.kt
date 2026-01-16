package blbl.cat3399.core.model

data class BangumiSeason(
    val seasonId: Long,
    val seasonTypeName: String?,
    val title: String,
    val coverUrl: String?,
    val badge: String?,
    val badgeEp: String?,
    val progressText: String?,
    val totalCount: Int?,
    val lastEpIndex: Int?,
    val lastEpId: Long?,
    val newestEpIndex: Int?,
    val isFinish: Boolean?,
)

data class BangumiEpisode(
    val epId: Long,
    val aid: Long?,
    val cid: Long?,
    val bvid: String?,
    val title: String,
    val longTitle: String,
    val coverUrl: String?,
    val badge: String?,
)

data class BangumiSeasonDetail(
    val seasonId: Long,
    val title: String,
    val coverUrl: String?,
    val subtitle: String?,
    val evaluate: String?,
    val ratingScore: Double?,
    val views: Long?,
    val danmaku: Long?,
    val episodes: List<BangumiEpisode>,
    val progressLastEpId: Long?,
)
