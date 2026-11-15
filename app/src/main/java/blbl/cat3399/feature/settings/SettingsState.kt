package blbl.cat3399.feature.settings

import blbl.cat3399.core.update.ApkUpdater

sealed interface TestUpdateCheckState {
    data object Idle : TestUpdateCheckState

    data object Checking : TestUpdateCheckState

    data class Latest(val latestVersion: String) : TestUpdateCheckState

    data class UpdateAvailable(val update: ApkUpdater.RemoteUpdate) : TestUpdateCheckState {
        val latestVersion: String
            get() = update.versionName
    }

    data class Error(val message: String) : TestUpdateCheckState
}

class SettingsState {
    var currentSectionIndex: Int = -1

    var lastFocusedLeftIndex: Int = 0
    var pendingRestoreRightId: SettingId? = null
    var pendingRestoreLeftIndex: Int? = null
    var pendingRestoreBack: Boolean = false
    var focusRequestToken: Int = 0
    var sectionRenderToken: Int = 0
    private val lastFocusedRightIds = mutableMapOf<Int, SettingId>()

    var cacheSizeBytes: Long? = null

    var testUpdateCheckState: TestUpdateCheckState = TestUpdateCheckState.Idle
    var testUpdateCheckedAtMs: Long = -1L

    fun rememberFocusedRightId(id: SettingId) {
        val sectionIndex = currentSectionIndex
        if (sectionIndex >= 0) {
            lastFocusedRightIds[sectionIndex] = id
        }
    }

    fun lastFocusedRightIdForCurrentSection(): SettingId? {
        return lastFocusedRightIds[currentSectionIndex]
    }
}
