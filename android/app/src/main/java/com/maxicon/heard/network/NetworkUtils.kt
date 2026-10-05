package com.maxicon.heard.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
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
        if (!wifiAddress.isNullOrBlank()) return wifiAddress
        return fallbackIpAddress()
    }

    private fun wifiIpAddress(context: Context): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wifiIpAddressApi31(context)
        } else {
            wifiIpAddressLegacy(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun wifiIpAddressApi31(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val linkProps: LinkProperties = cm.getLinkProperties(network) ?: return null
        return linkProps.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    @Suppress("DEPRECATION")
    private fun wifiIpAddressLegacy(context: Context): String? {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val rawIp = wifiManager.connectionInfo?.ipAddress ?: return null
        if (rawIp == 0) return null
        return "${rawIp and 0xff}.${rawIp shr 8 and 0xff}.${rawIp shr 16 and 0xff}.${rawIp shr 24 and 0xff}"
    }

    private fun fallbackIpAddress(): String? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        interfaces.forEach { iface ->
            Collections.list(iface.inetAddresses).forEach { address ->
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    return address.hostAddress
                }
            }
        }
        return null
    }
}
