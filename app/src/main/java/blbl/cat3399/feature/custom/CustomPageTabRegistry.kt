package blbl.cat3399.feature.custom

import androidx.fragment.app.Fragment
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.prefs.CustomPageConfig
import blbl.cat3399.core.prefs.CustomPageTabConfig
import blbl.cat3399.feature.category.CategoryZones
import blbl.cat3399.feature.home.PgcRecommendGridFragment
import blbl.cat3399.feature.live.LiveGridFragment
import blbl.cat3399.feature.video.VideoGridFragment

data class CustomPageTabOption(
    val stableKey: String,
    val label: String,
    val config: CustomPageTabConfig,
)

data class CustomPageAddGroup(
    val key: String,
    val label: String,
    val directOption: CustomPageTabOption? = null,
)

data class CustomPageSearchSourceKind(
    val sourceType: String,
    val label: String,
    val order: Int,
)

data class CustomPageResolvedTab(
    val stableKey: String,
    val title: String,
    val createFragment: () -> Fragment,
)

object CustomPageTabRegistry {
    const val GROUP_RECOMMEND = "recommend"
    const val GROUP_CATEGORY = "category"
    const val GROUP_SEARCH = "search"
    const val GROUP_DYNAMIC = "dynamic"
    const val GROUP_LIVE = "live"
    const val GROUP_MY = "my"

    const val TYPE_HOME_RECOMMEND = "home_recommend"
    const val TYPE_HOME_POPULAR = "home_popular"
    const val TYPE_HOME_BANGUMI = "home_bangumi"
    const val TYPE_HOME_CINEMA = "home_cinema"
    const val TYPE_CATEGORY_ALL = "category_all"
    const val TYPE_CATEGORY_ZONE = "category_zone"
    const val TYPE_SEARCH_VIDEO = "search_video"
    const val TYPE_SEARCH_LIVE = "search_live"
    const val TYPE_SEARCH_CINEMA = "search_cinema"
    const val TYPE_SEARCH_BANGUMI = "search_bangumi"
    const val TYPE_DYNAMIC_VIDEO = "dynamic_video"
    const val TYPE_MY_HISTORY = "my_history"
    const val TYPE_MY_FAV = "my_fav"
    const val TYPE_MY_BANGUMI = "my_bangumi"
    const val TYPE_MY_DRAMA = "my_drama"
    const val TYPE_MY_TOVIEW = "my_toview"
    const val TYPE_MY_LIKE = "my_like"
    const val TYPE_LIVE_RECOMMEND = "live_recommend"
    const val TYPE_LIVE_FOLLOWING = "live_following"

    private data class GroupDescriptor(
        val key: String,
        val label: String,
        val order: Int,
        val directAdd: Boolean = false,
    )

    private data class Descriptor(
        val stableKey: String,
        val managerLabel: String,
        val tabTitle: String,
        val groupKey: String,
        val itemOrder: Int,
        val requiresLogin: Boolean = false,
        val showInAddMenu: Boolean = true,
        val createFragment: () -> Fragment,
    )

    private val groups =
        listOf(
            GroupDescriptor(key = GROUP_RECOMMEND, label = "推荐", order = 10),
            GroupDescriptor(key = GROUP_CATEGORY, label = "分类", order = 20),
            GroupDescriptor(key = GROUP_SEARCH, label = "搜索", order = 30),
            GroupDescriptor(key = GROUP_DYNAMIC, label = "动态", order = 40, directAdd = true),
            GroupDescriptor(key = GROUP_LIVE, label = "直播", order = 50),
            GroupDescriptor(key = GROUP_MY, label = "我的", order = 60),
        )

    private val searchSourceKinds =
        listOf(
            CustomPageSearchSourceKind(sourceType = TYPE_SEARCH_VIDEO, label = "普通视频", order = 10),
            CustomPageSearchSourceKind(sourceType = TYPE_SEARCH_LIVE, label = "直播", order = 20),
            CustomPageSearchSourceKind(sourceType = TYPE_SEARCH_CINEMA, label = "影视", order = 30),
            CustomPageSearchSourceKind(sourceType = TYPE_SEARCH_BANGUMI, label = "番剧", order = 40),
        )

    fun isEnabled(
        config: CustomPageConfig,
        isLoggedIn: Boolean = BiliClient.cookies.hasSessData(),
    ): Boolean {
        return config.enabled && resolvedTabs(config = config, isLoggedIn = isLoggedIn).isNotEmpty()
    }

    fun resolvedTabs(
        config: CustomPageConfig,
        isLoggedIn: Boolean = BiliClient.cookies.hasSessData(),
    ): List<CustomPageResolvedTab> {
        if (!config.enabled) return emptyList()

        val out = ArrayList<CustomPageResolvedTab>(config.tabs.size)
        val seen = HashSet<String>(config.tabs.size * 2)
        config.tabs.forEach { tab ->
            val descriptor = descriptorFor(tab) ?: return@forEach
            if (descriptor.requiresLogin && !isLoggedIn) return@forEach
            if (!seen.add(descriptor.stableKey)) return@forEach
            out.add(
                CustomPageResolvedTab(
                    stableKey = descriptor.stableKey,
                    title = descriptor.tabTitle,
                    createFragment = descriptor.createFragment,
                ),
            )
        }
        return out
    }

    fun availableAddGroups(
        config: CustomPageConfig,
        isLoggedIn: Boolean = BiliClient.cookies.hasSessData(),
    ): List<CustomPageAddGroup> {
        return groups.sortedBy { it.order }.mapNotNull { group ->
            if (group.key == GROUP_SEARCH) {
                return@mapNotNull if (availableSearchSourceKinds(config).isEmpty()) {
                    null
                } else {
                    CustomPageAddGroup(key = group.key, label = group.label)
                }
            }

            val options = availableAddOptionsForGroup(group.key, config = config, isLoggedIn = isLoggedIn)
            if (options.isEmpty()) {
                null
            } else {
                CustomPageAddGroup(
                    key = group.key,
                    label = group.label,
                    directOption = options.singleOrNull().takeIf { group.directAdd },
                )
            }
        }
    }

    fun availableAddOptionsForGroup(
        groupKey: String,
        config: CustomPageConfig,
        isLoggedIn: Boolean = BiliClient.cookies.hasSessData(),
    ): List<CustomPageTabOption> {
        if (groupKey == GROUP_SEARCH) return emptyList()
        val existingStableKeys = config.tabs.mapTo(HashSet(config.tabs.size * 2)) { stableKeyFor(it) }
        return addMenuConfigs()
            .mapNotNull { supportedConfig ->
                val descriptor = descriptorFor(supportedConfig) ?: return@mapNotNull null
                if (!descriptor.showInAddMenu || descriptor.groupKey != groupKey) return@mapNotNull null
                if (stableKeyFor(supportedConfig) in existingStableKeys) return@mapNotNull null
                val label =
                    if (descriptor.requiresLogin && !isLoggedIn) {
                        "${descriptor.tabTitle}（需登录后显示）"
                    } else {
                        descriptor.tabTitle
                    }
                CustomPageTabOption(
                    stableKey = stableKeyFor(supportedConfig),
                    label = label,
                    config = supportedConfig,
                )
            }
            .sortedWith(compareBy({ descriptorFor(it.config)?.itemOrder ?: Int.MAX_VALUE }, { it.label }))
    }

    fun availableSearchSourceKinds(config: CustomPageConfig): List<CustomPageSearchSourceKind> {
        return searchSourceKinds
            .filter { availableSearchHistoryOptions(it.sourceType, config).isNotEmpty() }
            .sortedBy { it.order }
    }

    fun availableSearchHistoryOptions(
        sourceType: String,
        config: CustomPageConfig,
    ): List<CustomPageTabOption> {
        val kind = searchKindForSourceType(sourceType) ?: return emptyList()
        val existingStableKeys = config.tabs.mapTo(HashSet(config.tabs.size * 2)) { stableKeyFor(it) }
        return searchHistoryConfigs(kind)
            .mapNotNull { supportedConfig ->
                val descriptor = descriptorFor(supportedConfig) ?: return@mapNotNull null
                if (stableKeyFor(supportedConfig) in existingStableKeys) return@mapNotNull null
                CustomPageTabOption(
                    stableKey = stableKeyFor(supportedConfig),
                    label = descriptor.tabTitle,
                    config = supportedConfig,
                )
            }
            .sortedWith(compareBy({ descriptorFor(it.config)?.itemOrder ?: Int.MAX_VALUE }, { it.label }))
    }

    fun managerLabel(
        config: CustomPageTabConfig,
        isLoggedIn: Boolean = BiliClient.cookies.hasSessData(),
    ): String {
        val descriptor = descriptorFor(config)
        return when {
            descriptor == null -> invalidLabelFor(config)
            descriptor.requiresLogin && !isLoggedIn -> "${descriptor.managerLabel}（需登录后显示）"
            else -> descriptor.managerLabel
        }
    }

    fun settingsLabelForConfig(
        config: CustomPageTabConfig,
        isLoggedIn: Boolean = BiliClient.cookies.hasSessData(),
    ): String = managerLabel(config = config, isLoggedIn = isLoggedIn)

    fun stableKeyFor(config: CustomPageTabConfig): String {
        val sourceType = config.sourceType.trim().lowercase()
        val sourceKey = config.sourceKey?.trim().orEmpty()
        return if (sourceKey.isBlank()) sourceType else "$sourceType:$sourceKey"
    }

    private fun addMenuConfigs(): List<CustomPageTabConfig> {
        return buildList {
            add(CustomPageTabConfig(sourceType = TYPE_HOME_RECOMMEND))
            add(CustomPageTabConfig(sourceType = TYPE_HOME_POPULAR))
            add(CustomPageTabConfig(sourceType = TYPE_HOME_BANGUMI))
            add(CustomPageTabConfig(sourceType = TYPE_HOME_CINEMA))
            add(CustomPageTabConfig(sourceType = TYPE_CATEGORY_ALL))
            CategoryZones.defaultZones
                .mapNotNull { zone ->
                    zone.tid?.let { tid ->
                        CustomPageTabConfig(sourceType = TYPE_CATEGORY_ZONE, sourceKey = tid.toString())
                    }
                }.forEach(::add)
            add(CustomPageTabConfig(sourceType = TYPE_DYNAMIC_VIDEO))
            add(CustomPageTabConfig(sourceType = TYPE_LIVE_RECOMMEND))
            add(CustomPageTabConfig(sourceType = TYPE_LIVE_FOLLOWING))
            add(CustomPageTabConfig(sourceType = TYPE_MY_HISTORY))
            add(CustomPageTabConfig(sourceType = TYPE_MY_FAV))
            add(CustomPageTabConfig(sourceType = TYPE_MY_BANGUMI))
            add(CustomPageTabConfig(sourceType = TYPE_MY_DRAMA))
            add(CustomPageTabConfig(sourceType = TYPE_MY_TOVIEW))
            add(CustomPageTabConfig(sourceType = TYPE_MY_LIKE))
        }
    }

    private fun descriptorFor(config: CustomPageTabConfig): Descriptor? {
        return when (config.sourceType) {
            TYPE_HOME_RECOMMEND ->
                Descriptor(
                    stableKey = TYPE_HOME_RECOMMEND,
                    managerLabel = "推荐-推荐",
                    tabTitle = "推荐",
                    groupKey = GROUP_RECOMMEND,
                    itemOrder = 10,
                    createFragment = { VideoGridFragment.newRecommend() },
                )

            TYPE_HOME_POPULAR ->
                Descriptor(
                    stableKey = TYPE_HOME_POPULAR,
                    managerLabel = "推荐-热门",
                    tabTitle = "热门",
                    groupKey = GROUP_RECOMMEND,
                    itemOrder = 20,
                    createFragment = { VideoGridFragment.newPopular() },
                )

            TYPE_HOME_BANGUMI ->
                Descriptor(
                    stableKey = TYPE_HOME_BANGUMI,
                    managerLabel = "推荐-番剧",
                    tabTitle = "番剧",
                    groupKey = GROUP_RECOMMEND,
                    itemOrder = 30,
                    createFragment = { PgcRecommendGridFragment.newBangumi() },
                )

            TYPE_HOME_CINEMA ->
                Descriptor(
                    stableKey = TYPE_HOME_CINEMA,
                    managerLabel = "推荐-影视",
                    tabTitle = "影视",
                    groupKey = GROUP_RECOMMEND,
                    itemOrder = 40,
                    createFragment = { PgcRecommendGridFragment.newCinema() },
                )

            TYPE_CATEGORY_ALL -> {
                val zone = CategoryZones.findAll() ?: return null
                Descriptor(
                    stableKey = stableKeyFor(config),
                    managerLabel = "分类-${zone.title}",
                    tabTitle = zone.title,
                    groupKey = GROUP_CATEGORY,
                    itemOrder = categoryOrderForTid(null),
                    createFragment = { VideoGridFragment.newPopular() },
                )
            }

            TYPE_CATEGORY_ZONE -> {
                val tid = config.sourceKey?.toIntOrNull()?.takeIf { it > 0 } ?: return null
                val zone = CategoryZones.findByTid(tid) ?: return null
                Descriptor(
                    stableKey = stableKeyFor(config),
                    managerLabel = "分类-${zone.title}",
                    tabTitle = zone.title,
                    groupKey = GROUP_CATEGORY,
                    itemOrder = categoryOrderForTid(tid),
                    createFragment = { VideoGridFragment.newRegion(tid) },
                )
            }

            TYPE_SEARCH_VIDEO,
            TYPE_SEARCH_LIVE,
            TYPE_SEARCH_CINEMA,
            TYPE_SEARCH_BANGUMI,
            -> {
                val kind = searchKindForSourceType(config.sourceType) ?: return null
                val keyword = config.sourceKey?.trim()?.takeIf { it.isNotBlank() } ?: return null
                Descriptor(
                    stableKey = stableKeyFor(config),
                    managerLabel = "搜索-${kind.label}-$keyword",
                    tabTitle = keyword,
                    groupKey = GROUP_SEARCH,
                    itemOrder = kind.order * 1000 + searchHistoryOrderForKeyword(keyword),
                    createFragment =
                        {
                            when (kind.sourceType) {
                                TYPE_SEARCH_LIVE -> LiveGridFragment.newSearch(keyword)
                                TYPE_SEARCH_CINEMA -> PgcRecommendGridFragment.newSearchCinema(keyword)
                                TYPE_SEARCH_BANGUMI -> PgcRecommendGridFragment.newSearchBangumi(keyword)
                                else -> VideoGridFragment.newSearch(keyword)
                            }
                        },
                )
            }

            TYPE_DYNAMIC_VIDEO ->
                Descriptor(
                    stableKey = TYPE_DYNAMIC_VIDEO,
                    managerLabel = "动态",
                    tabTitle = "动态",
                    groupKey = GROUP_DYNAMIC,
                    itemOrder = 10,
                    createFragment = { CustomDynamicVideoFragment.newInstance() },
                )

            TYPE_LIVE_RECOMMEND ->
                Descriptor(
                    stableKey = TYPE_LIVE_RECOMMEND,
                    managerLabel = "直播-推荐",
                    tabTitle = "推荐",
                    groupKey = GROUP_LIVE,
                    itemOrder = 10,
                    createFragment = { LiveGridFragment.newRecommend() },
                )

            TYPE_LIVE_FOLLOWING ->
                Descriptor(
                    stableKey = TYPE_LIVE_FOLLOWING,
                    managerLabel = "直播-关注",
                    tabTitle = "关注",
                    groupKey = GROUP_LIVE,
                    itemOrder = 20,
                    requiresLogin = true,
                    createFragment = { LiveGridFragment.newFollowing() },
                )

            TYPE_MY_HISTORY ->
                Descriptor(
                    stableKey = TYPE_MY_HISTORY,
                    managerLabel = "我的-历史",
                    tabTitle = "历史",
                    groupKey = GROUP_MY,
                    itemOrder = 10,
                    requiresLogin = true,
                    createFragment = { CustomMyPageHostFragment.newHistory() },
                )

            TYPE_MY_FAV ->
                Descriptor(
                    stableKey = TYPE_MY_FAV,
                    managerLabel = "我的-收藏",
                    tabTitle = "收藏",
                    groupKey = GROUP_MY,
                    itemOrder = 20,
                    requiresLogin = true,
                    createFragment = { CustomMyPageHostFragment.newFav() },
                )

            TYPE_MY_BANGUMI ->
                Descriptor(
                    stableKey = TYPE_MY_BANGUMI,
                    managerLabel = "我的-追番",
                    tabTitle = "追番",
                    groupKey = GROUP_MY,
                    itemOrder = 30,
                    requiresLogin = true,
                    createFragment = { CustomMyPageHostFragment.newBangumi() },
                )

            TYPE_MY_DRAMA ->
                Descriptor(
                    stableKey = TYPE_MY_DRAMA,
                    managerLabel = "我的-追剧",
                    tabTitle = "追剧",
                    groupKey = GROUP_MY,
                    itemOrder = 40,
                    requiresLogin = true,
                    createFragment = { CustomMyPageHostFragment.newDrama() },
                )

            TYPE_MY_TOVIEW ->
                Descriptor(
                    stableKey = TYPE_MY_TOVIEW,
                    managerLabel = "我的-稍后再看",
                    tabTitle = "稍后再看",
                    groupKey = GROUP_MY,
                    itemOrder = 50,
                    requiresLogin = true,
                    createFragment = { CustomMyPageHostFragment.newToView() },
                )

            TYPE_MY_LIKE ->
                Descriptor(
                    stableKey = TYPE_MY_LIKE,
                    managerLabel = "我的-最近点赞",
                    tabTitle = "最近点赞",
                    groupKey = GROUP_MY,
                    itemOrder = 60,
                    requiresLogin = true,
                    createFragment = { CustomMyPageHostFragment.newLike() },
                )

            else -> null
        }
    }

    private fun searchHistoryConfigs(kind: CustomPageSearchSourceKind): List<CustomPageTabConfig> {
        return BiliClient.prefs.searchHistory.mapNotNull { keyword ->
            keyword.trim().takeIf { it.isNotBlank() }?.let {
                CustomPageTabConfig(sourceType = kind.sourceType, sourceKey = it)
            }
        }
    }

    private fun searchKindForSourceType(sourceType: String): CustomPageSearchSourceKind? {
        val type = sourceType.trim().lowercase()
        return searchSourceKinds.firstOrNull { it.sourceType == type }
    }

    private fun searchHistoryOrderForKeyword(keyword: String): Int {
        val index = BiliClient.prefs.searchHistory.indexOfFirst { it.equals(keyword, ignoreCase = true) }
        return if (index >= 0) 100 + index else Int.MAX_VALUE
    }

    private fun categoryOrderForTid(tid: Int?): Int {
        val index = CategoryZones.defaultZones.indexOfFirst { it.tid == tid }
        return if (index >= 0) 100 + index else Int.MAX_VALUE
    }

    private fun invalidLabelFor(config: CustomPageTabConfig): String {
        val sourceType = config.sourceType.ifBlank { "unknown" }
        val sourceKey = config.sourceKey?.takeIf { it.isNotBlank() }
        return if (sourceKey == null) {
            "无效来源($sourceType)"
        } else {
            "无效来源($sourceType:$sourceKey)"
        }
    }
}
