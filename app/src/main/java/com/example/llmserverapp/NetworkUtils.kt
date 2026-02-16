package com.example.llmserverapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkUtils {
    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getLocalIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"

           interfaces.toList()
               .flatMap { it.inetAddresses.toList() }
               .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
               ?.hostAddress
               ?: "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }
}