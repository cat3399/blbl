package blbl.cat3399.feature.video.comment

import android.view.KeyEvent
import android.view.View
import blbl.cat3399.core.image.ImageLoader
import blbl.cat3399.core.ui.FocusReturn

internal data class VideoCommentImageViewerViews(
    val container: View,
    val image: VideoCommentImageView,
    val previous: View,
    val next: View,
)

internal class VideoCommentImageViewerController(
    private val views: VideoCommentImageViewerViews,
    private val currentFocusProvider: () -> View?,
    private val fallbackFocusProvider: () -> View?,
) {
    private val focusReturn = FocusReturn()
    private var pictures: List<VideoCommentPicture> = emptyList()
    private var index: Int = 0

    init {
        views.container.visibility = View.GONE
        views.image.setSourceDimensions(width = null, height = null)
        ImageLoader.loadInto(views.image, null)
        views.image.resetViewport()

        views.container.setOnClickListener {
            if (!isVisible()) return@setOnClickListener
            close()
        }

        views.previous.setOnClickListener {
            if (!isVisible()) return@setOnClickListener
            if (views.image.isZoomed()) return@setOnClickListener
            previous()
        }
        views.next.setOnClickListener {
            if (!isVisible()) return@setOnClickListener
            if (views.image.isZoomed()) return@setOnClickListener
            next()
        }
        views.image.onNavigatePrevious = {
            if (isVisible() && !views.image.isZoomed()) {
                previous()
            }
        }
        views.image.onNavigateNext = {
            if (isVisible() && !views.image.isZoomed()) {
                next()
            }
        }
        views.image.onBlankAreaTap = {
            if (isVisible()) {
                close()
            }
        }
        views.image.onZoomStateChanged = {
            if (isVisible()) {
                updateNavigationUi()
            }
        }
    }

    fun isVisible(): Boolean = views.container.visibility == View.VISIBLE

    fun open(pictures: List<VideoCommentPicture>, startIndex: Int = 0): Boolean {
        val safePictures =
            pictures.mapNotNull { picture ->
                val url = picture.url.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (url == picture.url) picture else picture.copy(url = url)
            }
        if (safePictures.isEmpty()) return false

        this.pictures = safePictures
        index = startIndex.coerceIn(0, safePictures.lastIndex)
        focusReturn.capture(currentFocusProvider())

        views.container.visibility = View.VISIBLE
        views.container.bringToFront()
        views.container.invalidate()
        views.container.requestLayout()
        views.container.requestFocus()
        render()
        return true
    }

    fun close(restoreFocus: Boolean = true) {
        if (!isVisible()) return

        views.container.visibility = View.GONE
        views.image.setSourceDimensions(width = null, height = null)
        ImageLoader.loadInto(views.image, null)
        views.image.resetViewport()
        pictures = emptyList()
        index = 0

        if (!restoreFocus) {
            focusReturn.clear()
            return
        }

        focusReturn.restoreAndClear(fallback = fallbackFocusProvider(), postOnFail = false)
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isVisible()) return false

        val keyCode = event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    close()
                    return true
                }

                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_GUIDE,
                -> return true

                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> {
                    views.image.toggleDpadZoom()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (views.image.isZoomed()) {
                        views.image.panLeft()
                    } else {
                        previous()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (views.image.isZoomed()) {
                        views.image.panRight()
                    } else {
                        next()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (views.image.isZoomed()) {
                        views.image.panUp()
                    }
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (views.image.isZoomed()) {
                        views.image.panDown()
                    }
                    return true
                }
            }
        }

        if (event.action == KeyEvent.ACTION_UP) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_SETTINGS,
                KeyEvent.KEYCODE_INFO,
                KeyEvent.KEYCODE_GUIDE,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                -> return true
            }
        }

        return false
    }

    private fun render() {
        val currentPictures = pictures
        if (currentPictures.isEmpty()) {
            close()
            return
        }

        val idx = index.coerceIn(0, currentPictures.lastIndex)
        index = idx
        val picture = currentPictures[idx]
        views.image.resetViewport()
        views.image.setSourceDimensions(width = picture.width, height = picture.height)
        ImageLoader.loadInto(views.image, picture.url)
        updateNavigationUi()
    }

    private fun previous() {
        if (pictures.size <= 1) return
        if (index <= 0) return
        index -= 1
        render()
    }

    private fun next() {
        if (pictures.size <= 1) return
        if (index >= pictures.lastIndex) return
        index += 1
        render()
    }

    private fun updateNavigationUi() {
        val showNavigation = pictures.size > 1 && !views.image.isZoomed()
        views.previous.visibility =
            if (showNavigation && index > 0) View.VISIBLE else View.GONE
        views.next.visibility =
            if (showNavigation && index < pictures.lastIndex) View.VISIBLE else View.GONE
    }
}
