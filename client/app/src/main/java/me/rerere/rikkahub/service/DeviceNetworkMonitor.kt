package me.rerere.rikkahub.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class NetworkChangedException : CancellationException("设备网络已断开或切换，已保留内容，请恢复网络后继续")

/** A new default route may invalidate an in-flight stream or tool request. Never replay it. */
internal suspend fun <T> monitorDeviceNetwork(context: Context, block: suspend () -> T): T = coroutineScope {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val initial = manager.activeNetwork
    val scope = this
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (initial != null && network != initial) scope.cancel(NetworkChangedException())
        }

        override fun onLost(network: Network) {
            if (network != initial) return
            scope.launch {
                delay(750)
                val active = manager.activeNetwork
                val capabilities = active?.let(manager::getNetworkCapabilities)
                if (active != initial || capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true)
                    scope.cancel(NetworkChangedException())
            }
        }
    }
    manager.registerDefaultNetworkCallback(callback)
    try { block() } finally { manager.unregisterNetworkCallback(callback) }
}
