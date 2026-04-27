package blbl.cat3399.feature.live

object LiveAreas {
    // Parent areas from https://api.live.bilibili.com/room/v1/Area/getList, checked on 2026-04-26.
    val defaultParents: List<Parent> =
        listOf(
            parent(id = 2, name = "网游"),
            parent(id = 3, name = "手游"),
            parent(id = 6, name = "单机游戏"),
            parent(id = 1, name = "娱乐"),
            parent(id = 5, name = "电台"),
            parent(id = 9, name = "虚拟主播"),
            parent(id = 14, name = "聊天室"),
            parent(id = 10, name = "生活"),
            parent(id = 11, name = "知识"),
            parent(id = 13, name = "赛事"),
            parent(id = 15, name = "互动玩法"),
        )

    data class Parent(
        val id: Int,
        val name: String,
    )

    private fun parent(id: Int, name: String): Parent = Parent(id = id, name = name)
}
