package blbl.cat3399.core.model

sealed interface HistoryEntry {
    fun stableKey(): String

    data class Video(
        val card: VideoCard,
    ) : HistoryEntry {
        override fun stableKey(): String = card.stableKey()
    }

    data class Live(
        val card: LiveRoomCard,
    ) : HistoryEntry {
        override fun stableKey(): String = "live:${card.roomId}"
    }
}
