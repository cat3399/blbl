package blbl.cat3399.feature.player

import android.content.Intent
import android.view.View
import androidx.lifecycle.lifecycleScope
import blbl.cat3399.R
import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.BiliApiException
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.ThemeColor
import blbl.cat3399.feature.login.QrLoginActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun PlayerActivity.setupUpQuickCardActions() {
    binding.btnUpQuickProfile.setOnClickListener {
        openCurrentUpDetail()
        setControlsVisible(true)
    }
    binding.btnUpQuickFollow.setOnClickListener {
        onUpQuickFollowClicked()
        setControlsVisible(true)
    }
}

internal fun PlayerActivity.applyUpFollowStateFromView(viewData: JSONObject) {
    currentUpFollowed = parseUpFollowStateFromViewData(viewData)
    updateUpQuickCardUi()
    refreshUpFollowStateIfNeeded(force = false)
}

internal fun PlayerActivity.refreshUpFollowStateIfNeeded(force: Boolean) {
    val mid = currentUpMid.takeIf { it > 0L } ?: return
    val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
    val isSelf = selfMid != null && selfMid == mid
    if (isSelf) {
        currentUpFollowed = null
        updateUpQuickCardUi()
        return
    }
    if (!BiliClient.cookies.hasSessData()) {
        currentUpFollowed = null
        updateUpQuickCardUi()
        return
    }
    if (!force && currentUpFollowed != null) {
        updateUpQuickCardUi()
        return
    }

    upFollowStateJob?.cancel()
    val token = ++upFollowStateToken
    upFollowStateJob =
        lifecycleScope.launch {
            try {
                val isFollowed =
                    withContext(Dispatchers.IO) {
                        runCatching { BiliApi.spaceAccInfo(mid).isFollowed }.getOrNull()
                    }
                if (token != upFollowStateToken) return@launch
                if (currentUpMid != mid) return@launch
                if (isFollowed != null) currentUpFollowed = isFollowed
            } finally {
                if (token == upFollowStateToken) upFollowStateJob = null
                updateUpQuickCardUi()
            }
        }
}

internal fun PlayerActivity.onUpQuickFollowClicked() {
    val mid = currentUpMid
    if (mid <= 0L) {
        AppToast.show(this, "未获取到 UP 主信息")
        return
    }
    if (!BiliClient.cookies.hasSessData()) {
        startActivity(Intent(this, QrLoginActivity::class.java))
        AppToast.show(this, "登录后才能关注")
        return
    }
    if (upFollowActionJob?.isActive == true) return

    val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
    if (selfMid != null && selfMid == mid) return

    val wantFollow = currentUpFollowed != true
    upFollowActionInFlight = true
    updateUpQuickCardUi()

    upFollowActionJob =
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    BiliApi.modifyRelation(fid = mid, act = if (wantFollow) 1 else 2, reSrc = 11)
                }
                if (currentUpMid != mid) return@launch
                currentUpFollowed = wantFollow
                AppToast.show(this@onUpQuickFollowClicked, if (wantFollow) "已关注" else "已取关")
            } catch (t: Throwable) {
                if (t is CancellationException) return@launch
                val raw = (t as? BiliApiException)?.apiMessage?.takeIf { it.isNotBlank() } ?: t.message.orEmpty()
                val msg = if (raw == "missing_csrf") "登录态不完整，请重新登录" else raw
                AppToast.show(this@onUpQuickFollowClicked, if (msg.isBlank()) "操作失败" else msg)
            } finally {
                if (currentUpMid == mid) upFollowActionInFlight = false
                upFollowActionJob = null
                updateUpQuickCardUi()
            }
        }
}

internal fun PlayerActivity.updateUpQuickCardUi() {
    val hasUp = currentUpMid > 0L
    binding.cardUpQuick.visibility = if (hasUp) View.VISIBLE else View.GONE
    if (!hasUp) return

    val upName = currentUpName?.trim().orEmpty().ifBlank { "UP主" }
    binding.tvUpQuickName.text = upName
    ImageLoader.loadInto(binding.ivUpQuickAvatar, currentUpAvatar)

    val selfMid = BiliClient.cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
    val isSelf = selfMid != null && selfMid == currentUpMid
    binding.btnUpQuickFollow.visibility = if (isSelf) View.GONE else View.VISIBLE
    if (isSelf) return

    val isFollowed = currentUpFollowed == true
    binding.btnUpQuickFollow.isEnabled = !upFollowActionInFlight
    binding.btnUpQuickFollow.text =
        if (upFollowActionInFlight) {
            getString(R.string.player_loading)
        } else if (isFollowed) {
            getString(R.string.player_up_quick_followed)
        } else {
            getString(R.string.player_up_quick_follow)
        }

    val fg =
        if (isFollowed) {
            ThemeColor.resolve(this, android.R.attr.textColorSecondary, R.color.blbl_text_secondary)
        } else {
            ThemeColor.resolve(this, android.R.attr.textColorPrimary, R.color.blbl_text)
        }
    binding.btnUpQuickFollow.setTextColor(fg)
}

private fun parseUpFollowStateFromViewData(viewData: JSONObject): Boolean? {
    val owner = viewData.optJSONObject("owner")
    val reqUser = viewData.optJSONObject("req_user")

    val ownerAttention = owner?.optInt("attention", -1) ?: -1
    if (ownerAttention >= 0) return ownerAttention == 1

    val reqAttention = reqUser?.optInt("attention", -1) ?: -1
    if (reqAttention >= 0) return reqAttention == 1

    val reqFollow = reqUser?.optInt("follow", -1) ?: -1
    if (reqFollow >= 0) return reqFollow == 1

    val reqFollowStatus = reqUser?.optInt("follow_status", -1) ?: -1
    if (reqFollowStatus >= 0) return reqFollowStatus > 0

    val relationStatus = owner?.optJSONObject("relation")?.optInt("status", -1) ?: -1
    if (relationStatus >= 0) return relationStatus > 0

    return null
}
