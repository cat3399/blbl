package blbl.cat3399.feature.category

import blbl.cat3399.core.model.Zone
import blbl.cat3399.core.prefs.AppPrefs

object CategoryZones {
    const val KEY_ALL = "all"
    private const val KEY_ZONE_PREFIX = "zone:"

    val defaultZones: List<Zone> =
        listOf(
            Zone("全站", null),
            Zone("动画", 1),
            Zone("音乐", 3),
            Zone("舞蹈", 129),
            Zone("游戏", 4),
            Zone("知识", 36),
            Zone("科技", 188),
            Zone("运动", 234),
            Zone("汽车", 223),
            Zone("生活", 160),
            Zone("美食", 211),
            Zone("动物圈", 217),
            Zone("鬼畜", 119),
            Zone("时尚", 155),
            Zone("资讯", 202),
            Zone("娱乐", 5),
            Zone("影视", 181),
        )

    fun findByTid(tid: Int): Zone? = defaultZones.firstOrNull { it.tid == tid }

    fun findAll(): Zone? = defaultZones.firstOrNull { it.tid == null }

    fun stableKeyFor(zone: Zone): String = zone.tid?.let { KEY_ZONE_PREFIX + it } ?: KEY_ALL

    fun visibleZones(prefs: AppPrefs): List<Zone> {
        val selectedKeys = prefs.mainCategoryVisibleTabs
        if (selectedKeys.isEmpty()) return defaultZones
        val selected = selectedKeys.toSet()
        return defaultZones.filter { stableKeyFor(it) in selected }.ifEmpty { defaultZones }
    }
}
