package blbl.cat3399.core.model

data class LiveAreaParent(
    val id: Int,
    val name: String,
    val children: List<Child>,
) {
    data class Child(
        val id: Int,
        val parentId: Int,
        val name: String,
        val hot: Boolean,
        val coverUrl: String?,
    )
}

data class LiveRoomCard(
    val roomId: Long,
    val uid: Long,
    val title: String,
    val uname: String,
    val coverUrl: String,
    val faceUrl: String?,
    val online: Long,
    val isLive: Boolean,
    val parentAreaId: Int?,
    val parentAreaName: String?,
    val areaId: Int?,
    val areaName: String?,
    val keyframe: String?,
)

