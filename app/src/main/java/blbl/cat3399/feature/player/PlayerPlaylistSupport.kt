package blbl.cat3399.feature.player

import blbl.cat3399.core.api.video.VideoDetail
import blbl.cat3399.core.api.video.VideoDetailStat
import blbl.cat3399.core.api.video.VideoUgcSeason
import blbl.cat3399.core.api.video.VideoUgcSeasonEpisode
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.feature.video.VideoCardVisibilityFilter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class PlaylistParsed(
    val items: List<PlayerPlaylistItem>,
    val uiCards: List<VideoCard>,
)

internal interface PlayerPlaylistContinuation {
    val hasMore: Boolean

    suspend fun loadNextPage(): PlaylistParsed
}

internal data class PlayerPlaylistAppendPage<Cursor>(
    val cards: List<VideoCard>,
    val nextCursor: Cursor,
    val hasMore: Boolean,
)

internal data class VideoCardPlaylistPage<Cursor>(
    val cards: List<VideoCard>,
    val nextCursor: Cursor,
    val hasMore: Boolean,
    val canAdvance: Boolean = hasMore,
)

internal fun <Cursor> buildFreshVideoCardPlaylistContinuation(
    seedCards: List<VideoCard>,
    nextCursor: Cursor,
    hasMore: Boolean,
    playlistItemFactory: (VideoCard) -> PlayerPlaylistItem,
    fetchPage: suspend (cursor: Cursor) -> VideoCardPlaylistPage<Cursor>,
): PlayerPlaylistContinuation? {
    return buildVideoCardPlaylistContinuation(
        seedCards = seedCards,
        nextCursor = nextCursor,
        hasMore = hasMore,
        playlistItemFactory = playlistItemFactory,
    ) { cursor, loadedStableKeys ->
        loadFreshVideoCardPlaylistPage(
            cursor = cursor,
            loadedStableKeys = loadedStableKeys,
            fetchPage = fetchPage,
        )
    }
}

private suspend fun <Cursor> loadFreshVideoCardPlaylistPage(
    cursor: Cursor,
    loadedStableKeys: Set<String>,
    fetchPage: suspend (cursor: Cursor) -> VideoCardPlaylistPage<Cursor>,
): PlayerPlaylistAppendPage<Cursor> {
    var currentCursor = cursor
    while (true) {
        val page = fetchPage(currentCursor)
        val visibleCards = VideoCardVisibilityFilter.filterVisibleFresh(page.cards, loadedStableKeys)
        if (visibleCards.isNotEmpty() || !page.canAdvance) {
            return PlayerPlaylistAppendPage(
                cards = visibleCards,
                nextCursor = page.nextCursor,
                hasMore = page.hasMore,
            )
        }
        currentCursor = page.nextCursor
    }
}

internal fun <Cursor> buildVideoCardPlaylistContinuation(
    seedCards: List<VideoCard>,
    nextCursor: Cursor,
    hasMore: Boolean,
    playlistItemFactory: (VideoCard) -> PlayerPlaylistItem,
    fetchPage: suspend (cursor: Cursor, loadedStableKeys: Set<String>) -> PlayerPlaylistAppendPage<Cursor>,
): PlayerPlaylistContinuation? {
    if (!hasMore) return null
    return VideoCardPlaylistContinuation(
        nextCursor = nextCursor,
        hasMore = hasMore,
        loadedStableKeys = seedCards.mapTo(HashSet(seedCards.size)) { it.stableKey() },
        playlistItemFactory = playlistItemFactory,
        fetchPage = fetchPage,
    )
}

private class VideoCardPlaylistContinuation<Cursor>(
    private var nextCursor: Cursor,
    hasMore: Boolean,
    private val loadedStableKeys: HashSet<String>,
    private val playlistItemFactory: (VideoCard) -> PlayerPlaylistItem,
    private val fetchPage: suspend (cursor: Cursor, loadedStableKeys: Set<String>) -> PlayerPlaylistAppendPage<Cursor>,
) : PlayerPlaylistContinuation {
    private var endReached: Boolean = !hasMore

    override val hasMore: Boolean
        get() = !endReached

    override suspend fun loadNextPage(): PlaylistParsed {
        if (endReached) return PlaylistParsed(emptyList(), emptyList())
        val currentCursor = nextCursor
        val page = fetchPage(currentCursor, loadedStableKeys)
        nextCursor = page.nextCursor
        if (!page.hasMore || (page.cards.isEmpty() && page.nextCursor == currentCursor)) {
            endReached = true
        }
        val parsed = parseVideoCardsToPlaylistParsed(page.cards, playlistItemFactory)
        parsed.uiCards.forEach { loadedStableKeys.add(it.stableKey()) }
        return parsed
    }
}

internal fun parseVideoCardsToPlaylistParsed(
    cards: List<VideoCard>,
    playlistItemFactory: (VideoCard) -> PlayerPlaylistItem,
): PlaylistParsed {
    if (cards.isEmpty()) return PlaylistParsed(emptyList(), emptyList())
    val items = ArrayList<PlayerPlaylistItem>(cards.size)
    val uiCards = ArrayList<VideoCard>(cards.size)
    for (card in cards) {
        val item = playlistItemFactory(card)
        val hasArchiveId = item.bvid.isNotBlank() || (item.aid ?: 0L) > 0L
        if (!hasArchiveId) continue
        items.add(item)
        uiCards.add(card)
    }
    return PlaylistParsed(items = items, uiCards = uiCards)
}

internal fun parseUgcSeasonPlaylistFromDetailWithUiCards(ugcSeason: VideoUgcSeason): PlaylistParsed {
    val cap = ugcSeason.epCount?.coerceAtLeast(0) ?: 0
    val outItems = ArrayList<PlayerPlaylistItem>(cap)
    val outCards = ArrayList<VideoCard>(cap)

    for (section in ugcSeason.sections) {
        for (episode in section.episodes) {
            if (episode.bvid.isBlank()) continue
            val rawTitle = episode.title?.trim()?.takeIf { it.isNotBlank() }
            outItems.add(
                PlayerPlaylistItem(
                    bvid = episode.bvid,
                    cid = episode.cid,
                    aid = episode.aid,
                    title = rawTitle,
                ),
            )

            outCards.add(
                VideoCard(
                    bvid = episode.bvid,
                    cid = episode.cid,
                    aid = episode.aid,
                    title = rawTitle ?: "视频 ${outItems.size}",
                    coverUrl = episode.coverUrl.orEmpty(),
                    durationSec = episode.durationSec ?: 0,
                    ownerName = episode.owner?.name.orEmpty(),
                    ownerFace = episode.owner?.avatarUrl,
                    ownerMid = episode.owner?.mid?.takeIf { it > 0L },
                    view = statCount(episode.stat, "view"),
                    danmaku = statCount(episode.stat, "danmaku"),
                    pubDate = episode.pubDateSec,
                    pubDateText = null,
                ),
            )
        }
    }

    return PlaylistParsed(items = outItems, uiCards = outCards)
}

internal fun parseMultiPagePlaylistFromDetailWithUiCards(detail: VideoDetail, bvid: String, aid: Long?): PlaylistParsed {
    if (detail.pages.size <= 1) return PlaylistParsed(emptyList(), emptyList())

    val outItems = ArrayList<PlayerPlaylistItem>(detail.pages.size)
    val outCards = ArrayList<VideoCard>(detail.pages.size)
    val owner = detail.owner

    for ((index, pageObj) in detail.pages.withIndex()) {
        val page = pageObj.page.takeIf { it > 0 } ?: (index + 1)
        val part = pageObj.title?.trim().orEmpty()
        val title =
            if (part.isBlank()) {
                "P$page"
            } else {
                "P$page $part"
            }
        outItems.add(
            PlayerPlaylistItem(
                bvid = bvid,
                cid = pageObj.cid,
                aid = aid,
                title = title,
            ),
        )
        outCards.add(
            VideoCard(
                bvid = bvid,
                cid = pageObj.cid,
                aid = aid,
                title = title,
                coverUrl = detail.coverUrl.orEmpty(),
                durationSec = pageObj.durationSec ?: 0,
                ownerName = owner?.name.orEmpty(),
                ownerFace = owner?.avatarUrl,
                ownerMid = owner?.mid?.takeIf { it > 0L },
                view = statCount(detail.stat, "view"),
                danmaku = statCount(detail.stat, "danmaku"),
                pubDate = detail.pubDateSec,
                pubDateText = null,
            ),
        )
    }

    return PlaylistParsed(items = outItems, uiCards = outCards)
}

private fun statCount(stat: VideoDetailStat, key: String): Long? =
    when (key) {
        "view" -> stat.view
        "danmaku" -> stat.danmaku
        else -> null
    }

internal fun pickPlaylistIndexForCurrentMedia(list: List<PlayerPlaylistItem>, bvid: String, aid: Long?, cid: Long?): Int {
    val safeBvid = bvid.trim()
    if (cid != null && cid > 0) {
        val byCid = list.indexOfFirst { it.cid == cid }
        if (byCid >= 0) return byCid
    }
    if (aid != null && aid > 0) {
        val byAid = list.indexOfFirst { it.aid == aid }
        if (byAid >= 0) return byAid
    }
    if (safeBvid.isNotBlank()) {
        val byBvid = list.indexOfFirst { it.bvid == safeBvid }
        if (byBvid >= 0) return byBvid
    }
    return -1
}

internal fun isMultiPagePlaylist(list: List<PlayerPlaylistItem>, currentBvid: String): Boolean {
    if (list.size < 2) return false
    val bvid = currentBvid.trim().takeIf { it.isNotBlank() } ?: return false
    return list.all { it.bvid == bvid && (it.cid ?: 0L) > 0L }
}

internal data class PlayerPlaylist(
    var items: List<PlayerPlaylistItem>,
    var uiCards: List<VideoCard>,
    val source: String?,
    val createdAtMs: Long,
    var index: Int,
    var continuation: PlayerPlaylistContinuation? = null,
)

internal object PlayerPlaylistStore {
    private const val MAX_PLAYLISTS = 30

    private val store = ConcurrentHashMap<String, PlayerPlaylist>()
    private val order = ArrayDeque<String>()
    private val lock = Any()

    fun put(
        items: List<PlayerPlaylistItem>,
        index: Int,
        source: String? = null,
        uiCards: List<VideoCard> = emptyList(),
        continuation: PlayerPlaylistContinuation? = null,
    ): String {
        val outItems = ArrayList<PlayerPlaylistItem>(items.size)
        val outCards = ArrayList<VideoCard>(items.size)
        val hasCards = uiCards.isNotEmpty()

        for (i in items.indices) {
            val item = items[i]
            val hasArchiveId = item.bvid.isNotBlank() || (item.aid ?: 0L) > 0L
            if (!hasArchiveId) continue
            outItems.add(item)
            if (hasCards) {
                val card = uiCards.getOrNull(i)
                if (card != null) {
                    outCards.add(card)
                } else {
                    val fallbackTitle =
                        item.title?.trim()?.takeIf { it.isNotBlank() }
                            ?: "视频 ${outItems.size}"
                    outCards.add(
                        VideoCard(
                            bvid = item.bvid,
                            cid = item.cid,
                            aid = item.aid,
                            epId = item.epId,
                            seasonId = item.seasonId,
                            title = fallbackTitle,
                            coverUrl = "",
                            durationSec = 0,
                            ownerName = "",
                            ownerFace = null,
                            ownerMid = null,
                            view = null,
                            danmaku = null,
                            pubDate = null,
                            pubDateText = null,
                        ),
                    )
                }
            }
        }

        val safeIndex = index.coerceIn(0, (outItems.size - 1).coerceAtLeast(0))
        val token = UUID.randomUUID().toString()
        val playlist =
            PlayerPlaylist(
                items = outItems,
                uiCards = if (hasCards) outCards else emptyList(),
                source = source,
                createdAtMs = System.currentTimeMillis(),
                index = safeIndex,
                continuation = continuation,
            )
        store[token] = playlist
        synchronized(lock) {
            order.addLast(token)
            trimLocked()
        }
        AppLog.d(
            "PlayerPlaylistStore",
            "put size=${outItems.size} cards=${if (hasCards) outCards.size else 0} idx=$safeIndex source=${source.orEmpty()} token=${token.take(8)}",
        )
        return token
    }

    fun get(token: String): PlayerPlaylist? {
        if (token.isBlank()) return null
        return store[token]
    }

    fun updateIndex(token: String, index: Int) {
        if (token.isBlank()) return
        val p = store[token] ?: return
        p.index = index.coerceIn(0, (p.items.size - 1).coerceAtLeast(0))
    }

    fun sync(
        token: String,
        items: List<PlayerPlaylistItem>,
        uiCards: List<VideoCard>,
        continuation: PlayerPlaylistContinuation?,
    ) {
        if (token.isBlank()) return
        val p = store[token] ?: return
        p.items = items
        p.uiCards = uiCards
        p.continuation = continuation
        p.index = p.index.coerceIn(0, (p.items.size - 1).coerceAtLeast(0))
    }

    fun remove(token: String) {
        if (token.isBlank()) return
        store.remove(token)
        synchronized(lock) {
            order.remove(token)
        }
    }

    private fun trimLocked() {
        while (order.size > MAX_PLAYLISTS) {
            val oldest = order.removeFirstOrNull() ?: break
            store.remove(oldest)
        }
    }
}
