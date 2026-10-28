package com.example.meshchat.wifi

import android.content.Context
import android.content.pm.PackageManager

/** Vérifie si le téléphone peut utiliser le Wi-Fi Aware, et donne une phrase à afficher. Ne plante jamais. */
object WifiAwareSupport {
    fun status(context: Context): String {
        return try {
            val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
            if (!hasFeature) {
                "Wi-Fi Aware : non supporté par ce téléphone"
            } else {
                val manager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? android.net.wifi.aware.WifiAwareManager
                if (manager?.isAvailable == true) {
                    "Wi-Fi Aware : disponible"
                } else {
                    "Wi-Fi Aware : indisponible (activez le Wi-Fi)"
                }
            }
        } catch (e: Throwable) {
            "Wi-Fi Aware : vérification impossible"
        }
    }
}
