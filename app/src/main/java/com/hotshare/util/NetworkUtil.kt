package com.hotshare.util

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtil {

    /**
     * 获取热点局域网 IP（通常是 192.168.43.1 或 192.168.x.1）
     */
    fun getWlanIpAddress(context: Context): String {
        // 方法一：从 WifiManager 获取
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val wifiIp = intToIp(wifiInfo.ipAddress)
        if (wifiIp != "0.0.0.0") return wifiIp

        // 方法二：遍历网卡
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { nic ->
                nic.name.contains("wlan", ignoreCase = true) ||
                nic.name.contains("ap",  ignoreCase = true) ||
                nic.name.contains("p2p", ignoreCase = true)
            }
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.let { return it.hostAddress }

        // 兜底：最常见的热点 IP
        return "192.168.43.1"
    }

    /**
     * 判断当前联网是否只走热点（不碰蜂窝）
     */
    fun isOnWifiOnly(context: Context): Boolean {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled || wifiManager.is5GHzBandSupported
    }

    private fun intToIp(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }
}
