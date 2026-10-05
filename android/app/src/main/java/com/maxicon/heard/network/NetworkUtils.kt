package com.maxicon.heard.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    fun isNetworkAvailable(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun localIpAddress(context: Context): String? {
        val wifiAddress = wifiIpAddress(context)
        if (!wifiAddress.isNullOrBlank()) {
            return wifiAddress
        }
        return fallbackIpAddress()
    }

    private fun wifiIpAddress(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        val connection = wifiManager.connectionInfo ?: return null
        val rawIp = connection.ipAddress
        if (rawIp == 0) {
            return null
        }
        return buildString {
            append(rawIp and 0xff)
            append('.')
            append(rawIp shr 8 and 0xff)
            append('.')
            append(rawIp shr 16 and 0xff)
            append('.')
            append(rawIp shr 24 and 0xff)
        }
    }

    private fun fallbackIpAddress(): String? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        interfaces.forEach { iface ->
            val addresses = Collections.list(iface.inetAddresses)
            addresses.forEach { address ->
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    return address.hostAddress
                }
            }
        }
        return null
    }
}
