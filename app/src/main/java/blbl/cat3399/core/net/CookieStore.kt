package blbl.cat3399.core.net

import android.content.Context
import android.content.SharedPreferences
import blbl.cat3399.core.log.AppLog
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class CookieStore(
    context: Context,
) : CookieJar {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("blbl_cookie_store", Context.MODE_PRIVATE)

    private val store: ConcurrentHashMap<String, MutableList<Cookie>> = ConcurrentHashMap()

    init {
        loadFromDisk()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        for (cookie in cookies) {
            val key = cookie.domain
            val list = (store[key] ?: mutableListOf()).toMutableList()
            list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            list.add(cookie)
            store[key] = list
        }
        persistToDisk()
        AppLog.d("CookieStore", "saveFromResponse host=${url.host} +${cookies.size}")
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val list = store.values.flatten().filter { it.expiresAt >= now && it.matches(url) }
        if (list.isNotEmpty()) AppLog.v("CookieStore", "loadForRequest host=${url.host} cookies=${list.size}")
        return list
    }

    fun cookieHeaderFor(url: String): String? {
        val httpUrl = runCatching { url.toHttpUrlOrNull() }.getOrNull() ?: return null
        return cookieHeaderFor(httpUrl)
    }

    fun cookieHeaderFor(url: HttpUrl): String? {
        val cookies = loadForRequest(url).toMutableList()
        if (cookies.isEmpty()) return null
        cookies.sortWith(
            compareByDescending<Cookie> { it.path.length }
                .thenBy { it.name },
        )
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
    }

    fun hasSessData(): Boolean {
        val now = System.currentTimeMillis()
        return store.values.flatten().any { it.name == "SESSDATA" && it.expiresAt >= now }
    }

    fun getCookieValue(name: String): String? {
        val now = System.currentTimeMillis()
        return store.values.flatten().firstOrNull { it.name == name && it.expiresAt >= now }?.value
    }

    fun getCookie(name: String): Cookie? {
        val now = System.currentTimeMillis()
        return store.values.flatten().firstOrNull { it.name == name && it.expiresAt >= now }
    }

    fun upsert(cookie: Cookie) {
        val key = cookie.domain
        val list = (store[key] ?: mutableListOf()).toMutableList()
        list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
        list.add(cookie)
        store[key] = list
        persistToDisk()
    }

    fun upsertAll(cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        for (cookie in cookies) {
            val key = cookie.domain
            val list = (store[key] ?: mutableListOf()).toMutableList()
            list.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            list.add(cookie)
            store[key] = list
        }
        persistToDisk()
    }

    fun clearAll() {
        store.clear()
        prefs.edit().clear().apply()
    }

    fun exportSnapshotJson(includeExpired: Boolean = false): JSONObject {
        return buildJsonRoot(includeExpired = includeExpired)
    }

    fun replaceAllFromJson(
        root: JSONObject,
        sync: Boolean = false,
    ) {
        val parsed = parseJsonRoot(root)
        store.clear()
        store.putAll(parsed)
        persistToDisk(sync = sync)
    }

    private fun persistToDisk(sync: Boolean = false) {
        val editor = prefs.edit().putString("cookies", buildJsonRoot(includeExpired = true).toString())
        if (sync) {
            check(editor.commit()) { "保存 Cookie 失败" }
        } else {
            editor.apply()
        }
    }

    private fun loadFromDisk() {
        val raw = prefs.getString("cookies", null) ?: return
        runCatching {
            val root = JSONObject(raw)
            store.clear()
            store.putAll(parseJsonRoot(root))
            AppLog.i("CookieStore", "loadFromDisk hosts=${store.size}")
        }.onFailure {
            AppLog.w("CookieStore", "loadFromDisk failed; clearing", it)
            store.clear()
            prefs.edit().clear().apply()
        }
    }

    private fun buildJsonRoot(includeExpired: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        val root = JSONObject()
        for ((host, cookies) in store.entries) {
            val arr = JSONArray()
            cookies.forEach { cookie ->
                if (!includeExpired && cookie.expiresAt < now) return@forEach
                val obj = JSONObject()
                obj.put("name", cookie.name)
                obj.put("value", cookie.value)
                obj.put("domain", cookie.domain)
                obj.put("path", cookie.path)
                obj.put("expiresAt", cookie.expiresAt)
                obj.put("secure", cookie.secure)
                obj.put("httpOnly", cookie.httpOnly)
                obj.put("hostOnly", cookie.hostOnly)
                obj.put("persistent", cookie.persistent)
                arr.put(obj)
            }
            if (arr.length() > 0) root.put(host, arr)
        }
        return root
    }

    private fun parseJsonRoot(root: JSONObject): ConcurrentHashMap<String, MutableList<Cookie>> {
        val parsed = ConcurrentHashMap<String, MutableList<Cookie>>()
        val it = root.keys()
        while (it.hasNext()) {
            val domain = it.next()
            val arr = root.optJSONArray(domain) ?: continue
            val list = mutableListOf<Cookie>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val builder = Cookie.Builder()
                    .name(obj.getString("name"))
                    .value(obj.getString("value"))
                    .path(obj.optString("path", "/"))

                val cookieDomain = obj.optString("domain", domain)
                if (obj.optBoolean("hostOnly", false)) builder.hostOnlyDomain(cookieDomain) else builder.domain(cookieDomain)
                if (obj.optBoolean("secure", false)) builder.secure()
                if (obj.optBoolean("httpOnly", false)) builder.httpOnly()
                val expiresAt = obj.optLong("expiresAt", 0L)
                if (expiresAt > 0L) builder.expiresAt(expiresAt)
                list.add(builder.build())
            }
            if (list.isNotEmpty()) parsed[domain] = list
        }
        return parsed
    }
}
