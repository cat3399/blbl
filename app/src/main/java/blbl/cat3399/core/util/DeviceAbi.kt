package blbl.cat3399.core.util

import android.os.Build

object DeviceAbi {
    fun getSupportedAbis(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 21) {
            Build.SUPPORTED_ABIS
        } else {
            @Suppress("DEPRECATION")
            arrayOf(Build.CPU_ABI, Build.CPU_ABI2).filter { it.isNotEmpty() }.toTypedArray()
        }
    }

    fun getFirstAbi(): String = getSupportedAbis().firstOrNull().orEmpty()
}
