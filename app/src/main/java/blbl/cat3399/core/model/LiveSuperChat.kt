package blbl.cat3399.core.model

data class LiveSuperChat(
    val id: Long,
    val uid: Long,
    val userName: String,
    val userFaceUrl: String?,
    val userNameColor: String,
    val price: Long,
    val message: String,
    val backgroundImageUrl: String?,
    val backgroundColor: String,
    val backgroundBottomColor: String,
    val backgroundPriceColor: String,
    val messageFontColor: String,
    val startTimeSeconds: Long?,
    val endTimeSeconds: Long?,
    val durationSeconds: Long?,
)
