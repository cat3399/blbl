package blbl.cat3399.feature.player

import android.content.Intent
import android.view.View
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.image.ImageUrl
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.ThemeColor
import blbl.cat3399.databinding.ActivityPlayerBinding
import blbl.cat3399.feature.following.UpDetailActivity
import blbl.cat3399.feature.login.QrLoginActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PlayerUpQuickOwner(
    val mid: Long = 0L,
    val name: String? = null,
    val avatar: String? = null,
)

internal class PlayerUpQuickCardController(
    private val activity: BaseActivity,
    private val binding: ActivityPlayerBinding,
    private val isCardVisible: () -> Boolean,
    private val keepControlsVisible: () -> Unit,
    private val beforeOpenUpDetail: () -> Unit = {},
    private val onUiUpdated: () -> Unit = {},
) {
    private var owner = PlayerUpQuickOwner()
    private var followed: Boolean? = null
    private var actionInFlight: Boolean = false
    private var actionJob: Job? = null
    private var stateJob: Job? = null
    private var stateToken: Int = 0

    val currentMid: Long get() = owner.mid
    val currentName: String? get() = owner.name
    val currentAvatar: String? get() = owner.avatar
    val currentFollowed: Boolean? get() = followed
    val followActionInFlight: Boolean get() = actionInFlight

    fun setupActions() {
        binding.btnUpQuickProfile.setOnClickListener {
            openProfile()
            keepControlsVisible()
        }
        binding.btnUpQuickFollow.setOnClickListener {
            onFollowClicked()
            keepControlsVisible()
        }
    }

    fun setOwner(
        mid: Long,
        name: String?,
        avatar: String?,
        followed: Boolean? = this.followed,
    ) {
        val oldMid = owner.mid
        val safeMid = mid.takeIf { it > 0L } ?: 0L
        owner =
            PlayerUpQuickOwner(
                mid = safeMid,
                name = name?.trim()?.takeIf { it.isNotBlank() },
                avatar = avatar?.trim()?.takeIf { it.isNotBlank() },
            )
        this.followed =
            if (safeMid > 0L && safeMid == oldMid) {
                followed
            } else {
                null
            }
        if (safeMid <= 0L || safeMid != oldMid) release()
        updateUi()
    }

    fun applyFollowState(initialFollowed: Boolean?) {
        followed = initialFollowed
        updateUi()
        refreshFollowStateIfNeeded(force = false)
    }

    fun refreshFollowStateIfNeeded(force: Boolean) {
        val mid = owner.mid.takeIf { it > 0L } ?: return
        val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        val isSelf = selfMid != null && selfMid == mid
        if (isSelf) {
            followed = null
            updateUi()
            return
        }
        if (!BiliClient.cookies.hasSessData()) {
            followed = null
            updateUi()
            return
        }
        if (!force && followed != null) {
            updateUi()
            return
        }

        stateJob?.cancel()
        val token = ++stateToken
        stateJob =
            activity.lifecycleScope.launch {
                try {
                    val isFollowed =
                        withContext(Dispatchers.IO) {
                            runCatching { BiliApi.spaceAccInfo(mid).isFollowed }.getOrNull()
                        }
                    if (token != stateToken) return@launch
                    if (owner.mid != mid) return@launch
                    if (isFollowed != null) followed = isFollowed
                } finally {
                    if (token == stateToken) stateJob = null
                    updateUi()
                }
            }
    }

    fun onFollowClicked() {
        val mid = owner.mid
        if (mid <= 0L) {
            AppToast.show(activity, "未获取到 UP 主信息")
            return
        }
        if (!BiliClient.cookies.hasSessData()) {
            activity.startActivity(Intent(activity, QrLoginActivity::class.java))
            AppToast.show(activity, "登录后才能关注")
            return
        }
        if (actionJob?.isActive == true) return

        val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        if (selfMid != null && selfMid == mid) return

        val wantFollow = followed != true
        actionInFlight = true
        updateUi()

        actionJob =
            activity.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        BiliApi.modifyRelation(fid = mid, act = if (wantFollow) 1 else 2, reSrc = 11)
                    }
                    if (owner.mid != mid) return@launch
                    followed = wantFollow
                    AppToast.show(activity, if (wantFollow) "已关注" else "已取关")
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    val raw = (t as? BiliApiException)?.apiMessage?.takeIf { it.isNotBlank() } ?: t.message.orEmpty()
                    val msg = if (raw == "missing_csrf") "登录态不完整，请重新登录" else raw
                    AppToast.show(activity, if (msg.isBlank()) "操作失败" else msg)
                } finally {
                    if (owner.mid == mid) actionInFlight = false
                    actionJob = null
                    updateUi()
                }
            }
    }

    fun openProfile() {
        val current = owner
        if (current.mid <= 0L) {
            AppToast.show(activity, "未获取到 UP 主信息")
            return
        }
        beforeOpenUpDetail()
        activity.startActivity(
            Intent(activity, UpDetailActivity::class.java)
                .putExtra(UpDetailActivity.EXTRA_MID, current.mid)
                .apply {
                    current.name?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_NAME, it) }
                    current.avatar?.takeIf { it.isNotBlank() }?.let { putExtra(UpDetailActivity.EXTRA_AVATAR, it) }
                },
        )
    }

    fun updateUi() {
        val hasUp = owner.mid > 0L
        val showCard = hasUp && BiliClient.prefs.playerUpQuickCardEnabled && isCardVisible()
        binding.cardUpQuick.visibility = if (showCard) View.VISIBLE else View.GONE
        if (hasUp) {
            val upName = owner.name?.trim().orEmpty().ifBlank { "UP主" }
            binding.tvUpQuickName.text = upName
            ImageLoader.loadInto(binding.ivUpQuickAvatar, ImageUrl.avatar(owner.avatar))

            val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
            val isSelf = selfMid != null && selfMid == owner.mid
            binding.btnUpQuickFollow.visibility = if (isSelf) View.GONE else View.VISIBLE
            if (!isSelf) {
                val isFollowed = followed == true
                binding.btnUpQuickFollow.isEnabled = !actionInFlight
                binding.btnUpQuickFollow.text =
                    if (actionInFlight) {
                        activity.getString(R.string.player_loading)
                    } else if (isFollowed) {
                        activity.getString(R.string.player_up_quick_followed)
                    } else {
                        activity.getString(R.string.player_up_quick_follow)
                    }

                val fg =
                    if (isFollowed) {
                        ThemeColor.resolve(activity, android.R.attr.textColorSecondary, R.color.blbl_text_secondary)
                    } else {
                        ThemeColor.resolve(activity, android.R.attr.textColorPrimary, R.color.blbl_text)
                    }
                binding.btnUpQuickFollow.setTextColor(fg)
            }
        }
        onUiUpdated()
    }

    fun release() {
        actionInFlight = false
        actionJob?.cancel()
        actionJob = null
        stateJob?.cancel()
        stateJob = null
        stateToken++
    }
}

internal fun PlayerActivity.setupUpQuickCardActions() {
    upQuickCard.setupActions()
}

internal fun PlayerActivity.setUpQuickCardOwner(
    mid: Long,
    name: String?,
    avatar: String?,
    followed: Boolean? = currentUpFollowed,
) {
    upQuickCard.setOwner(mid = mid, name = name, avatar = avatar, followed = followed)
}

internal fun PlayerActivity.applyUpFollowState(initialFollowed: Boolean?) {
    upQuickCard.applyFollowState(initialFollowed)
}

internal fun PlayerActivity.refreshUpFollowStateIfNeeded(force: Boolean) {
    upQuickCard.refreshFollowStateIfNeeded(force = force)
}

internal fun PlayerActivity.onUpQuickFollowClicked() {
    upQuickCard.onFollowClicked()
}

internal fun PlayerActivity.openUpQuickCardProfile() {
    upQuickCard.openProfile()
}

internal fun PlayerActivity.updateUpQuickCardUi() {
    upQuickCard.updateUi()
}

internal fun PlayerActivity.releaseUpQuickCardJobs() {
    upQuickCard.release()
}
