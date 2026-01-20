package blbl.cat3399.core.tv

import android.view.KeyEvent

object RemoteKeys {
    fun isRefreshKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_REFRESH || keyCode == KeyEvent.KEYCODE_F5
    }
}

