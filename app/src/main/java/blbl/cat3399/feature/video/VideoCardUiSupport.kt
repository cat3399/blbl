package blbl.cat3399.feature.video

import android.content.Context
import android.content.Intent
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.model.VideoCard
import blbl.cat3399.core.ui.postIfAlive
import blbl.cat3399.core.ui.requestFocusAdapterPositionReliable
import blbl.cat3399.feature.player.PlayerPlaylistItem
import blbl.cat3399.feature.player.PlayerPlaylistStore
import blbl.cat3399.feature.player.PlayerPlaylistContinuation
import blbl.cat3399.feature.player.VideoCardPlaylistPage
import blbl.cat3399.feature.player.buildFreshVideoCardPlaylistContinuation

internal data class VideoCardPlaybackSource(
    val cards: List<VideoCard>,
    val source: String,
    val playlistItemFactory: (VideoCard) -> PlayerPlaylistItem = ::defaultVideoCardPlaylistItem,
    val continuation: PlayerPlaylistContinuation? = null,
)

internal class VideoCardPlaybackHandle(
    private val source: String,
    private val cardsProvider: () -> List<VideoCard>,
    private val playlistItemFactory: (VideoCard) -> PlayerPlaylistItem,
    private val continuationProvider: (List<VideoCard>) -> PlayerPlaylistContinuation?,
) {
    fun snapshot(): VideoCardPlaybackSource {
        val cards = cardsProvider()
        return VideoCardPlaybackSource(
            cards = cards,
            source = source,
            playlistItemFactory = playlistItemFactory,
            continuation = continuationProvider(cards),
        )
    }
}

internal fun buildVideoCardPlaybackHandle(
    source: String,
    cardsProvider: () -> List<VideoCard>,
    playlistItemFactory: (VideoCard) -> PlayerPlaylistItem = ::defaultVideoCardPlaylistItem,
    continuationProvider: (List<VideoCard>) -> PlayerPlaylistContinuation? = { null },
): VideoCardPlaybackHandle {
    return VideoCardPlaybackHandle(
        source = source,
        cardsProvider = cardsProvider,
        playlistItemFactory = playlistItemFactory,
        continuationProvider = continuationProvider,
    )
}

internal fun <Cursor> buildPagedVideoCardPlaybackHandle(
    source: String,
    cardsProvider: () -> List<VideoCard>,
    nextCursorProvider: () -> Cursor,
    hasMoreProvider: () -> Boolean,
    playlistItemFactory: (VideoCard) -> PlayerPlaylistItem = ::defaultVideoCardPlaylistItem,
    fetchPage: suspend (cursor: Cursor) -> VideoCardPlaylistPage<Cursor>,
): VideoCardPlaybackHandle {
    return buildVideoCardPlaybackHandle(
        source = source,
        cardsProvider = cardsProvider,
        playlistItemFactory = playlistItemFactory,
    ) { cards ->
        buildFreshVideoCardPlaylistContinuation(
            seedCards = cards,
            nextCursor = nextCursorProvider(),
            hasMore = hasMoreProvider(),
            playlistItemFactory = playlistItemFactory,
            fetchPage = fetchPage,
        )
    }
}

internal fun Context.buildVideoDetailIntent(
    card: VideoCard,
    playlistToken: String? = null,
    playlistIndex: Int? = null,
): Intent {
    return Intent(this, VideoDetailActivity::class.java)
        .putExtra(VideoDetailActivity.EXTRA_BVID, card.bvid)
        .putExtra(VideoDetailActivity.EXTRA_CID, card.cid ?: -1L)
        .apply { card.aid?.let { putExtra(VideoDetailActivity.EXTRA_AID, it) } }
        .putExtra(VideoDetailActivity.EXTRA_TITLE, card.title)
        .putExtra(VideoDetailActivity.EXTRA_COVER_URL, card.coverUrl)
        .apply {
            card.ownerName.takeIf { it.isNotBlank() }?.let { putExtra(VideoDetailActivity.EXTRA_OWNER_NAME, it) }
            card.ownerFace?.takeIf { it.isNotBlank() }?.let { putExtra(VideoDetailActivity.EXTRA_OWNER_AVATAR, it) }
            card.ownerMid?.takeIf { it > 0L }?.let { putExtra(VideoDetailActivity.EXTRA_OWNER_MID, it) }
        }
        .apply { playlistToken?.let { putExtra(VideoDetailActivity.EXTRA_PLAYLIST_TOKEN, it) } }
        .apply { playlistIndex?.let { putExtra(VideoDetailActivity.EXTRA_PLAYLIST_INDEX, it) } }
}

internal fun defaultVideoCardPlaylistItem(card: VideoCard): PlayerPlaylistItem =
    PlayerPlaylistItem(
        bvid = card.bvid,
        cid = card.cid,
        aid = card.aid,
        title = card.title,
    )

internal fun historyVideoCardPlaylistItem(card: VideoCard): PlayerPlaylistItem =
    PlayerPlaylistItem(
        bvid = card.bvid,
        cid = card.cid,
        epId = card.epId,
        aid = card.aid,
        title = card.title,
        seasonId = card.seasonId,
    )

internal fun List<VideoCard>.buildVideoCardPlaylistToken(
    index: Int,
    source: String,
    playlistItemFactory: (VideoCard) -> PlayerPlaylistItem = ::defaultVideoCardPlaylistItem,
    continuation: PlayerPlaylistContinuation? = null,
): String? {
    if (isEmpty()) return null
    val safeIndex = index.coerceIn(0, lastIndex)
    return PlayerPlaylistStore.put(
        items = map(playlistItemFactory),
        index = safeIndex,
        source = source,
        uiCards = this,
        continuation = continuation,
    )
}

private fun VideoCardPlaybackSource.buildPlaylistToken(index: Int): String? {
    return cards.buildVideoCardPlaylistToken(
        index = index,
        source = source,
        playlistItemFactory = playlistItemFactory,
        continuation = continuation,
    )
}

internal fun Context.openVideoDetailFromCards(
    cards: List<VideoCard>,
    position: Int,
    source: String,
    playlistItemFactory: (VideoCard) -> PlayerPlaylistItem = ::defaultVideoCardPlaylistItem,
    continuation: PlayerPlaylistContinuation? = null,
) {
    openVideoDetailFromPlaybackSource(
        playbackSource =
            VideoCardPlaybackSource(
                cards = cards,
                source = source,
                playlistItemFactory = playlistItemFactory,
                continuation = continuation,
            ),
        position = position,
    )
}

internal fun Context.openVideoDetailFromPlaybackHandle(
    playbackHandle: VideoCardPlaybackHandle,
    position: Int,
) {
    openVideoDetailFromPlaybackSource(playbackHandle.snapshot(), position)
}

internal fun Context.openVideoDetailFromPlaybackSource(
    playbackSource: VideoCardPlaybackSource,
    position: Int,
) {
    val cards = playbackSource.cards
    if (cards.isEmpty()) return
    val safePosition = position.coerceIn(0, cards.lastIndex)
    val token = playbackSource.buildPlaylistToken(index = safePosition) ?: return
    startActivity(buildVideoDetailIntent(cards[safePosition], playlistToken = token, playlistIndex = safePosition))
}

internal fun Context.openPlayerFromPlaybackHandle(
    playbackHandle: VideoCardPlaybackHandle,
    position: Int,
    configurePlayerIntent: Intent.(VideoCard) -> Unit = {},
) {
    openPlayerFromPlaybackSource(
        playbackSource = playbackHandle.snapshot(),
        position = position,
        configurePlayerIntent = configurePlayerIntent,
    )
}

internal fun Context.openPlayerFromPlaybackSource(
    playbackSource: VideoCardPlaybackSource,
    position: Int,
    configurePlayerIntent: Intent.(VideoCard) -> Unit = {},
) {
    val cards = playbackSource.cards
    if (cards.isEmpty()) return
    val safePosition = position.coerceIn(0, cards.lastIndex)
    val card = cards[safePosition]
    val token = playbackSource.buildPlaylistToken(index = safePosition) ?: return
    startActivity(
        Intent(this, blbl.cat3399.feature.player.PlayerActivity::class.java)
            .putExtra(blbl.cat3399.feature.player.PlayerActivity.EXTRA_BVID, card.bvid)
            .putExtra(blbl.cat3399.feature.player.PlayerActivity.EXTRA_CID, card.cid ?: -1L)
            .putExtra(blbl.cat3399.feature.player.PlayerActivity.EXTRA_PLAYLIST_TOKEN, token)
            .putExtra(blbl.cat3399.feature.player.PlayerActivity.EXTRA_PLAYLIST_INDEX, safePosition)
            .apply { card.aid?.takeIf { it > 0L }?.let { putExtra(blbl.cat3399.feature.player.PlayerActivity.EXTRA_AID, it) } }
            .apply { configurePlayerIntent(card) },
    )
}

internal fun Context.openVideoFromPlaybackHandle(
    playbackHandle: VideoCardPlaybackHandle,
    position: Int,
    openDetailBeforePlay: Boolean,
    canOpenDetail: (VideoCard) -> Boolean = { true },
    configurePlayerIntent: Intent.(VideoCard) -> Unit = {},
) {
    val playbackSource = playbackHandle.snapshot()
    val cards = playbackSource.cards
    if (cards.isEmpty()) return
    val safePosition = position.coerceIn(0, cards.lastIndex)
    val card = cards[safePosition]
    if (openDetailBeforePlay && canOpenDetail(card)) {
        openVideoDetailFromPlaybackSource(playbackSource, safePosition)
    } else {
        openPlayerFromPlaybackSource(
            playbackSource = playbackSource,
            position = safePosition,
            configurePlayerIntent = configurePlayerIntent,
        )
    }
}

internal fun RecyclerView.removeVideoCardAndRestoreFocus(
    adapter: VideoCardAdapter,
    stableKey: String,
    isAlive: () -> Boolean,
    onEmpty: (() -> Unit)? = null,
): Boolean {
    val removedIndex = adapter.removeByStableKey(stableKey)
    if (removedIndex < 0) return false

    postIfAlive(isAlive = isAlive) {
        val itemCount = adapter.itemCount
        if (itemCount <= 0) {
            onEmpty?.invoke()
            requestFocus()
            return@postIfAlive
        }

        requestFocusAdapterPositionReliable(
            position = removedIndex.coerceIn(0, itemCount - 1),
            smoothScroll = false,
            isAlive = isAlive,
            onFocused = {},
        )
    }
    return true
}
