package blbl.cat3399.feature.category

import blbl.cat3399.core.model.Zone
import blbl.cat3399.core.prefs.AppPrefs

object CategoryZones {
    const val KEY_ALL = "all"
    private const val KEY_ZONE_PREFIX = "rank:"

    val defaultZones: List<Zone> =
        listOf(
            Zone("全站", null),
            Zone("动画", 1005),
            Zone("音乐", 1003),
            Zone("舞蹈", 1004),
            Zone("游戏", 1008),
            Zone("知识", 1010),
            Zone("科技", 1012),
            Zone("运动", 1018),
            Zone("汽车", 1013),
            Zone("美食", 1020),
            Zone("动物", 1024),
            Zone("鬼畜", 1007),
            Zone("时尚", 1014),
            Zone("娱乐", 1002),
            Zone("影视", 1001),
        )

    private val legacyTidToRankRid: Map<Int, Int> =
        mapOf(
            1 to 1005,
            3 to 1003,
            129 to 1004,
            4 to 1008,
            36 to 1010,
            188 to 1012,
            234 to 1018,
            223 to 1013,
            211 to 1020,
            217 to 1024,
            119 to 1007,
            155 to 1014,
            5 to 1002,
            181 to 1001,
        )

    fun findByRid(rid: Int): Zone? = defaultZones.firstOrNull { it.rid == rid }

    fun rankRidForLegacyTid(tid: Int): Int? = legacyTidToRankRid[tid]

    fun findAll(): Zone? = defaultZones.firstOrNull { it.rid == null }

    fun stableKeyFor(zone: Zone): String = zone.rid?.let { KEY_ZONE_PREFIX + it } ?: KEY_ALL

    fun visibleZones(prefs: AppPrefs): List<Zone> {
        val selectedKeys = prefs.mainCategoryVisibleTabs
        if (selectedKeys.isEmpty()) return defaultZones
        val selected = selectedKeys.toSet()
        return defaultZones.filter { stableKeyFor(it) in selected }.ifEmpty { defaultZones }
    }
}
