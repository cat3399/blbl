package blbl.cat3399.core.account

import android.content.Context
import android.content.SharedPreferences
import blbl.cat3399.core.net.CookieStore
import blbl.cat3399.core.prefs.AppPrefs
import org.json.JSONArray
import org.json.JSONObject

class AccountSessionStore(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("blbl_account_sessions", Context.MODE_PRIVATE)

    data class AccountSummary(
        val id: String,
        val mid: Long,
        val name: String,
        val avatarUrl: String?,
        val isActive: Boolean,
        val updatedAtMs: Long,
    )

    private data class AccountRecord(
        val id: String,
        val mid: Long,
        val name: String,
        val avatarUrl: String?,
        val credentialPrefsJson: JSONObject,
        val cookiesJson: JSONObject,
        val updatedAtMs: Long,
    )

    @Synchronized
    fun accounts(): List<AccountSummary> {
        val activeId = activeAccountId()
        return readRecords()
            .sortedWith(
                compareByDescending<AccountRecord> { it.id == activeId }
                    .thenByDescending { it.updatedAtMs },
            )
            .map { it.toSummary(activeId) }
    }

    @Synchronized
    fun saveCurrentSessionAsActive(
        appPrefs: AppPrefs,
        cookies: CookieStore,
        name: String? = null,
        avatarUrl: String? = null,
        mid: Long? = null,
    ): AccountSummary? {
        val record = buildCurrentRecord(appPrefs = appPrefs, cookies = cookies, name = name, avatarUrl = avatarUrl, mid = mid) ?: return null
        val records = readRecords().filterNot { it.id == record.id } + record
        writeRecords(records = records, activeId = record.id)
        return record.toSummary(record.id)
    }

    @Synchronized
    fun switchToAccount(
        accountId: String,
        appPrefs: AppPrefs,
        cookies: CookieStore,
    ): AccountSummary? {
        val targetId = accountId.trim().takeIf { it.isNotBlank() } ?: return null
        val currentId = activeAccountId()
        if (currentId == targetId) return readRecords().firstOrNull { it.id == targetId }?.toSummary(targetId)

        val records =
            currentId
                ?.let { id ->
                    buildCurrentRecord(appPrefs = appPrefs, cookies = cookies, accountId = id)
                        ?.let { current -> readRecords().filterNot { it.id == current.id } + current }
                }
                ?: readRecords()
        val target = records.firstOrNull { it.id == targetId } ?: return null
        restoreRecord(target, appPrefs = appPrefs, cookies = cookies)
        writeRecords(records = records, activeId = target.id)
        return target.toSummary(target.id)
    }

    @Synchronized
    fun loadActiveAccount(
        appPrefs: AppPrefs,
        cookies: CookieStore,
    ): AccountSummary? {
        val activeId = activeAccountId() ?: return null
        val target = readRecords().firstOrNull { it.id == activeId } ?: return null
        restoreRecord(target, appPrefs = appPrefs, cookies = cookies)
        return target.toSummary(activeId)
    }

    @Synchronized
    fun removeActiveAccountAndActivateNext(
        appPrefs: AppPrefs,
        cookies: CookieStore,
    ): AccountSummary? {
        val currentId = activeAccountId() ?: currentMid(appPrefs, cookies)?.let(::accountIdForMid)
        val records = readRecords().filterNot { it.id == currentId }
        val next = records.maxByOrNull { it.updatedAtMs }
        writeRecords(records = records, activeId = next?.id)
        if (next == null) {
            clearCurrentSession(appPrefs = appPrefs, cookies = cookies)
            return null
        }
        restoreRecord(next, appPrefs = appPrefs, cookies = cookies)
        return next.toSummary(next.id)
    }

    @Synchronized
    fun clearAllAccountsAndCurrentSession(
        appPrefs: AppPrefs,
        cookies: CookieStore,
    ) {
        prefs.edit().clear().commit()
        clearCurrentSession(appPrefs = appPrefs, cookies = cookies)
    }

    @Synchronized
    fun exportBackupJson(): JSONObject =
        JSONObject()
            .put(KEY_SCHEMA, BACKUP_SCHEMA)
            .put(KEY_ACTIVE_ACCOUNT_ID, activeAccountId())
            .put(KEY_ACCOUNTS, JSONArray().also { arr -> readRecords().forEach { arr.put(it.toJson()) } })

    @Synchronized
    fun replaceAllFromBackupJson(root: JSONObject) {
        val records = parseRecords(root.optJSONArray(KEY_ACCOUNTS) ?: JSONArray())
        val activeId = root.stringOrNull(KEY_ACTIVE_ACCOUNT_ID)?.takeIf { id -> records.any { it.id == id } }
        writeRecords(records = records, activeId = activeId)
    }

    @Synchronized
    fun replaceWithCurrentSessionIfPresent(
        appPrefs: AppPrefs,
        cookies: CookieStore,
    ) {
        prefs.edit().clear().commit()
        saveCurrentSessionAsActive(appPrefs = appPrefs, cookies = cookies)
    }

    private fun buildCurrentRecord(
        appPrefs: AppPrefs,
        cookies: CookieStore,
        accountId: String? = null,
        name: String? = null,
        avatarUrl: String? = null,
        mid: Long? = null,
    ): AccountRecord? {
        if (!cookies.hasSessData() && appPrefs.appAuthSession == null) return null
        val resolvedMid = mid?.takeIf { it > 0L } ?: currentMid(appPrefs, cookies) ?: return null
        val id = accountId ?: accountIdForMid(resolvedMid)
        val old = readRecords().firstOrNull { it.id == id }
        return AccountRecord(
            id = id,
            mid = resolvedMid,
            name = name.clean() ?: old?.name.clean() ?: "UID $resolvedMid",
            avatarUrl = avatarUrl.clean() ?: old?.avatarUrl.clean(),
            credentialPrefsJson = appPrefs.exportCredentialsSnapshotJson(),
            cookiesJson = cookies.exportSnapshotJson(includeExpired = false),
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun restoreRecord(
        record: AccountRecord,
        appPrefs: AppPrefs,
        cookies: CookieStore,
    ) {
        appPrefs.replaceCredentialsFromSnapshotJson(JSONObject(record.credentialPrefsJson.toString()))
        cookies.replaceAllFromJson(JSONObject(record.cookiesJson.toString()), sync = true)
    }

    private fun clearCurrentSession(
        appPrefs: AppPrefs,
        cookies: CookieStore,
    ) {
        cookies.clearAll()
        appPrefs.webRefreshToken = null
        appPrefs.appAuthSession = null
        appPrefs.webCookieRefreshCheckedEpochDay = -1L
        appPrefs.biliTicketCheckedEpochDay = -1L
        appPrefs.gaiaVgateVVoucher = null
        appPrefs.gaiaVgateVVoucherSavedAtMs = -1L
    }

    private fun currentMid(
        appPrefs: AppPrefs,
        cookies: CookieStore,
    ): Long? =
        appPrefs.appAuthSession?.mid?.takeIf { it > 0L }
            ?: cookies.getCookieValue("DedeUserID")?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    private fun activeAccountId(): String? = prefs.getString(KEY_ACTIVE_ACCOUNT_ID, null)?.trim()?.takeIf { it.isNotBlank() }

    private fun readRecords(): List<AccountRecord> {
        val raw = prefs.getString(KEY_ACCOUNTS, null)?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching { parseRecords(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    private fun writeRecords(
        records: List<AccountRecord>,
        activeId: String?,
    ) {
        val validActiveId = activeId?.takeIf { id -> records.any { it.id == id } }
        val arr = JSONArray()
        records.sortedByDescending { it.updatedAtMs }.forEach { arr.put(it.toJson()) }
        val editor = prefs.edit().putString(KEY_ACCOUNTS, arr.toString())
        if (validActiveId == null) editor.remove(KEY_ACTIVE_ACCOUNT_ID) else editor.putString(KEY_ACTIVE_ACCOUNT_ID, validActiveId)
        editor.commit()
    }

    private fun parseRecords(arr: JSONArray): List<AccountRecord> {
        val out = ArrayList<AccountRecord>(arr.length())
        val seen = HashSet<String>(arr.length() * 2)
        for (index in 0 until arr.length()) {
            val obj = arr.optJSONObject(index) ?: continue
            val id = obj.stringOrNull(KEY_ID) ?: continue
            if (!seen.add(id)) continue
            val mid = obj.longOrNull(KEY_MID)?.takeIf { it > 0L } ?: continue
            out.add(
                AccountRecord(
                    id = id,
                    mid = mid,
                    name = obj.stringOrNull(KEY_NAME) ?: "UID $mid",
                    avatarUrl = obj.stringOrNull(KEY_AVATAR_URL),
                    credentialPrefsJson = obj.optJSONObject(KEY_PREFS) ?: continue,
                    cookiesJson = obj.optJSONObject(KEY_COOKIES) ?: continue,
                    updatedAtMs = obj.longOrNull(KEY_UPDATED_AT_MS)?.takeIf { it > 0L } ?: 0L,
                ),
            )
        }
        return out
    }

    private fun AccountRecord.toSummary(activeId: String?): AccountSummary =
        AccountSummary(
            id = id,
            mid = mid,
            name = name,
            avatarUrl = avatarUrl.clean(),
            isActive = id == activeId,
            updatedAtMs = updatedAtMs,
        )

    private fun AccountRecord.toJson(): JSONObject =
        JSONObject()
            .put(KEY_ID, id)
            .put(KEY_MID, mid)
            .put(KEY_NAME, name)
            .put(KEY_AVATAR_URL, avatarUrl)
            .put(KEY_PREFS, credentialPrefsJson)
            .put(KEY_COOKIES, cookiesJson)
            .put(KEY_UPDATED_AT_MS, updatedAtMs)

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").trim().takeIf { it.isNotBlank() }
    }

    private fun JSONObject.longOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private companion object {
        private const val BACKUP_SCHEMA = 1
        private const val KEY_SCHEMA = "schema"
        private const val KEY_ACTIVE_ACCOUNT_ID = "active_account_id"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ID = "id"
        private const val KEY_MID = "mid"
        private const val KEY_NAME = "name"
        private const val KEY_AVATAR_URL = "avatar_url"
        private const val KEY_PREFS = "prefs"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_UPDATED_AT_MS = "updated_at_ms"

        private fun accountIdForMid(mid: Long): String = "mid:$mid"
    }
}
