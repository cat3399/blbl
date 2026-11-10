package blbl.cat3399.core.prefs

enum class VideoFillMode(
    val prefValue: String,
    val label: String,
) {
    Fit("fit", "原比例适应"),
    Zoom("zoom", "等比铺满"),
    Stretch("stretch", "拉伸铺满"),
    ;

    companion object {
        val ordered: List<VideoFillMode> = entries.toList()

        fun fromPrefValue(value: String?): VideoFillMode =
            entries.firstOrNull { it.prefValue == value?.trim() } ?: Fit
    }
}
