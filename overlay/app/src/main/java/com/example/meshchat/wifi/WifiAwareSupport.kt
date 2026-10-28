package com.example.meshchat.wifi

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.WifiAwareManager

/** Vérifie si le téléphone peut utiliser le Wi-Fi Aware, et donne une phrase à afficher. */
object WifiAwareSupport {
    fun status(context: Context): String {
        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        if (!hasFeature) return "Wi-Fi Aware : non supporté par ce téléphone"
        val manager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        return if (manager?.isAvailable == true) {
            "Wi-Fi Aware : disponible"
        } else {
            "Wi-Fi Aware : indisponible (activez le Wi-Fi)"
        }
    }
}
