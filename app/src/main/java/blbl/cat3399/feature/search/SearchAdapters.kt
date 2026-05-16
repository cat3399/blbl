package blbl.cat3399.feature.search

import android.content.Intent
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.feature.following.FollowingGridAdapter
import blbl.cat3399.feature.following.UpDetailActivity
import blbl.cat3399.feature.following.openUpDetailFromVideoCard
import blbl.cat3399.feature.live.LivePlayerActivity
import blbl.cat3399.feature.live.LiveRoomAdapter
import blbl.cat3399.feature.my.BangumiFollowAdapter
import blbl.cat3399.feature.video.VideoCardActionController
import blbl.cat3399.feature.video.VideoCardAdapter
import blbl.cat3399.feature.video.VideoCardDismissBehavior

internal class SearchAdapters(
    private val fragment: SearchFragment,
    private val state: SearchState,
) {
    val keyAdapter: SearchKeyAdapter =
        SearchKeyAdapter { key ->
            fragment.onSearchKeyClicked(key)
        }

    val suggestAdapter: SearchSuggestAdapter =
        SearchSuggestAdapter { keyword ->
            fragment.onSearchKeywordClicked(keyword)
        }

    val hotAdapter: SearchHotAdapter =
        SearchHotAdapter { keyword ->
            fragment.onSearchKeywordClicked(keyword)
        }

    val videoAdapter: VideoCardAdapter

    val mediaAdapter: BangumiFollowAdapter =
        BangumiFollowAdapter { position, season ->
            state.pendingRestoreMediaPos = position
            val isDrama = state.tabForIndex(state.currentTabIndex) == SearchTab.Media
            fragment.openBangumiDetail(season = season, isDrama = isDrama)
        }

    val liveAdapter: LiveRoomAdapter =
        LiveRoomAdapter { _, room ->
            val context = fragment.context ?: return@LiveRoomAdapter
            if (!room.isLive) {
                AppToast.show(context, "未开播")
                return@LiveRoomAdapter
            }
            fragment.startActivity(
                Intent(context, LivePlayerActivity::class.java)
                    .putExtra(LivePlayerActivity.EXTRA_ROOM_ID, room.roomId)
                    .putExtra(LivePlayerActivity.EXTRA_TITLE, room.title)
                    .putExtra(LivePlayerActivity.EXTRA_UNAME, room.uname),
            )
        }

    val userAdapter: FollowingGridAdapter =
        FollowingGridAdapter { following ->
            val context = fragment.context ?: return@FollowingGridAdapter

            fun openProfile() {
                fragment.startActivity(
                    Intent(context, UpDetailActivity::class.java)
                        .putExtra(UpDetailActivity.EXTRA_MID, following.mid)
                        .putExtra(UpDetailActivity.EXTRA_NAME, following.name)
                        .putExtra(UpDetailActivity.EXTRA_AVATAR, following.avatarUrl)
                        .putExtra(UpDetailActivity.EXTRA_SIGN, following.sign),
                )
            }

            fun openLive() {
                val rid = following.liveRoomId.takeIf { it > 0L } ?: return
                fragment.startActivity(
                    Intent(context, LivePlayerActivity::class.java)
                        .putExtra(LivePlayerActivity.EXTRA_ROOM_ID, rid)
                        .putExtra(LivePlayerActivity.EXTRA_TITLE, "")
                        .putExtra(LivePlayerActivity.EXTRA_UNAME, following.name),
                )
            }

            if (following.isLive && following.liveRoomId > 0L) {
                AppPopup.singleChoice(
                    context = context,
                    title = context.getString(R.string.search_user_live_actions_title, following.name),
                    items =
                        listOf(
                            context.getString(R.string.search_user_action_enter_live),
                            context.getString(R.string.search_user_action_open_profile),
                        ),
                    checkedIndex = 0,
                ) { which, _ ->
                    when (which) {
                        0 -> openLive()
                        else -> openProfile()
                    }
                }
            } else {
                openProfile()
            }
        }

    init {
        val actionController =
            VideoCardActionController(
                context = fragment.requireContext(),
                scope = fragment.lifecycleScope,
                dismissBehavior = VideoCardDismissBehavior.LocalNotInterested,
                onOpenDetail = { _, pos -> fragment.openSearchVideoDetailAt(pos) },
                onOpenUp = { card -> fragment.openUpDetailFromVideoCard(card) },
                onCardRemoved = { stableKey ->
                    fragment.removeSearchVideoCardAndRestoreFocus(stableKey)
                },
            )
        videoAdapter =
            VideoCardAdapter(
                onClick = { _, pos ->
                    fragment.openSearchVideoAt(pos)
                },
                onLongClick = { card, _ ->
                    fragment.openUpDetailFromVideoCard(card)
                    true
                },
                actionDelegate = actionController,
            )
    }
}
