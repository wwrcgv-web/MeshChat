package com.example.meshchat.wifi

import android.content.Context
import android.content.pm.PackageManager

/** Vérifie si le téléphone peut utiliser le Wi-Fi Aware. Affiche la vraie erreur si elle survient. Ne plante jamais. */
object WifiAwareSupport {
    fun status(context: Context): String {
        return try {
            val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
            if (!hasFeature) {
                "Wi-Fi Aware : non supporté par ce téléphone"
            } else {
                val manager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? android.net.wifi.aware.WifiAwareManager
                when {
                    manager == null -> "Wi-Fi Aware : service absent (Wi-Fi désactivé ?)"
                    manager.isAvailable -> "Wi-Fi Aware : disponible"
                    else -> "Wi-Fi Aware : indisponible (activez le Wi-Fi)"
                }
            }
        } catch (e: Throwable) {
            "Wi-Fi Aware : erreur ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
        }
    }
}
