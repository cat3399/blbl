package blbl.cat3399.feature.player

import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.api.BiliApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ArchiveTripleActionState(
    val liked: Boolean,
    val coinCount: Int,
    val favored: Boolean,
) {
    val isSatisfied: Boolean
        get() = liked && coinCount > 0 && favored
}

data class ArchiveTripleActionResult(
    val state: ArchiveTripleActionState,
    val changedParts: Set<String>,
    val warnings: List<String>,
) {
    fun toastMessage(): String {
        return when {
            warnings.isEmpty() && state.isSatisfied -> "一键三连成功"
            warnings.isEmpty() && changedParts.isNotEmpty() -> "已完成${changedParts.joinToString("、")}"
            changedParts.isNotEmpty() -> "已完成${changedParts.joinToString("、")}，${warnings.joinToString("；")}"
            warnings.isNotEmpty() -> warnings.joinToString("；")
            else -> "操作失败"
        }
    }
}

suspend fun executeArchiveTripleAction(
    bvid: String,
    aid: Long?,
    selfMid: Long?,
    initialState: ArchiveTripleActionState,
    isStillValid: () -> Boolean = { true },
): ArchiveTripleActionResult {
    var liked = initialState.liked
    var coinCount = initialState.coinCount
    var favored = initialState.favored

    val changedParts = linkedSetOf<String>()
    val warnings = ArrayList<String>(2)

    fun ensureStillValid() {
        if (!isStillValid()) throw CancellationException("archive_triple_action_stale")
    }

    val needLike = !liked
    val needCoin = coinCount <= 0
    val needFav = !favored

    if (needCoin) {
        try {
            withContext(Dispatchers.IO) {
                BiliApi.coinAdd(
                    bvid = bvid,
                    aid = aid,
                    multiply = 1,
                    selectLike = needLike,
                )
            }
            ensureStillValid()
            coinCount = (coinCount + 1).coerceAtMost(2)
            changedParts += "投币"
            if (needLike) {
                liked = true
                changedParts += "点赞"
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val apiError = t as? BiliApiException
            if (apiError?.apiCode == 34005) {
                ensureStillValid()
                coinCount = coinCount.coerceAtLeast(2)
            } else {
                AppLog.w("VideoAction", "triple coin failed bvid=$bvid aid=$aid", t)
                warnings += "投币失败：${t.userMessage(defaultMessage = "操作失败")}"
            }
        }
    }

    if (!liked && needLike) {
        try {
            withContext(Dispatchers.IO) {
                BiliApi.archiveLike(bvid = bvid, aid = aid, like = true)
            }
            ensureStillValid()
            liked = true
            changedParts += "点赞"
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val apiError = t as? BiliApiException
            if (apiError?.apiCode == 65006) {
                ensureStillValid()
                liked = true
            } else {
                AppLog.w("VideoAction", "triple like failed bvid=$bvid aid=$aid", t)
                warnings += "点赞失败：${t.userMessage(defaultMessage = "操作失败")}"
            }
        }
    }

    if (needFav && !favored) {
        when {
            selfMid == null -> warnings += "未获取到账号信息，未执行收藏"
            aid == null -> warnings += "未获取到 aid，未执行收藏"
            else -> {
                val folders =
                    try {
                        withContext(Dispatchers.IO) {
                            BiliApi.favFoldersWithState(upMid = selfMid, rid = aid)
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        AppLog.w("VideoAction", "triple load favorites failed bvid=$bvid aid=$aid", t)
                        warnings += "收藏失败：${t.userMessage(defaultMessage = "加载收藏夹失败")}"
                        null
                    }

                ensureStillValid()

                val favFolders = folders.orEmpty()
                if (favFolders.any { it.favState }) {
                    favored = true
                } else {
                    val targetFolder = pickTripleActionFavFolder(favFolders)
                    if (targetFolder == null) {
                        warnings += "未找到可用收藏夹"
                    } else {
                        try {
                            withContext(Dispatchers.IO) {
                                BiliApi.favResourceDeal(
                                    rid = aid,
                                    addMediaIds = listOf(targetFolder.mediaId),
                                    delMediaIds = emptyList(),
                                )
                            }
                            ensureStillValid()
                            favored = true
                            changedParts += "收藏"
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            AppLog.w("VideoAction", "triple favorite failed bvid=$bvid aid=$aid", t)
                            warnings += "收藏失败：${t.userMessage(defaultMessage = "操作失败")}"
                        }
                    }
                }
            }
        }
    }

    return ArchiveTripleActionResult(
        state =
            ArchiveTripleActionState(
                liked = liked,
                coinCount = coinCount,
                favored = favored,
            ),
        changedParts = changedParts,
        warnings = warnings,
    )
}

fun pickTripleActionFavFolder(
    folders: List<BiliApi.FavFolderWithState>,
): BiliApi.FavFolderWithState? {
    return folders.firstOrNull { it.title == "默认收藏夹" } ?: folders.firstOrNull()
}

fun Throwable.userMessage(defaultMessage: String): String {
    val apiError = this as? BiliApiException
    return apiError?.apiMessage?.takeIf { it.isNotBlank() } ?: (message?.takeIf { it.isNotBlank() } ?: defaultMessage)
}
