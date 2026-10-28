package com.example.meshchat.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper

/**
 * Découverte Wi-Fi Aware : chaque téléphone publie le service « meshchat » avec son identifiant,
 * et écoute les autres. Ne transfère rien pour l'instant : on vérifie seulement qui est trouvé.
 */
@SuppressLint("MissingPermission")
class WifiAwareDiscovery(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var wifiSession: WifiAwareSession? = null
    private val sessions = mutableListOf<DiscoverySession>()
    private val peers = HashSet<String>()
    private var myId: String = ""
    private var onCount: ((Int) -> Unit)? = null
    private var onLog: ((String) -> Unit)? = null

    /** Démarre la découverte. [myIdHex] est l'identifiant de ce téléphone (texte hexadécimal). */
    fun start(myIdHex: String, countListener: (Int) -> Unit, logListener: (String) -> Unit) {
        myId = myIdHex
        onCount = countListener
        onLog = logListener
        try {
            val manager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
            if (manager == null || !manager.isAvailable) {
                log("Wi-Fi Aware : indisponible, démarrage annulé")
                return
            }
            manager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    wifiSession = session
                    log("Wi-Fi Aware : connecté")
                    startPublishAndSubscribe(session)
                }

                override fun onAttachFailed() {
                    log("Wi-Fi Aware : connexion refusée")
                }
            }, main)
        } catch (e: Throwable) {
            log("Wi-Fi Aware : erreur ${e.javaClass.simpleName}")
        }
    }

    private fun startPublishAndSubscribe(session: WifiAwareSession) {
        try {
            val publishConfig = PublishConfig.Builder()
                .setServiceName(SERVICE)
                .setServiceSpecificInfo(myId.toByteArray(Charsets.UTF_8))
                .build()
            session.publish(publishConfig, callback, main)

            val subscribeConfig = SubscribeConfig.Builder()
                .setServiceName(SERVICE)
                .build()
            session.subscribe(subscribeConfig, callback, main)
        } catch (e: Throwable) {
            log("Wi-Fi Aware : publication impossible (${e.javaClass.simpleName})")
        }
    }

    private val callback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(session: PublishDiscoverySession) {
            sessions.add(session)
            log("Wi-Fi Aware : service publié")
        }

        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
            sessions.add(session)
            log("Wi-Fi Aware : recherche active")
        }

        override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray, matchFilter: List<ByteArray>) {
            val id = String(serviceSpecificInfo, Charsets.UTF_8)
            if (id.isEmpty() || id == myId) return
            if (peers.add(id)) {
                log("Wi-Fi Aware : pair ${id.take(8)} trouvé")
                main.post { onCount?.invoke(peers.size) }
            }
        }

        override fun onSessionTerminated() {
            log("Wi-Fi Aware : session terminée")
        }
    }

    /** Arrête la découverte et ferme les sessions. */
    fun stop() {
        sessions.forEach { s -> try { s.close() } catch (e: Throwable) { } }
        sessions.clear()
        try { wifiSession?.close() } catch (e: Throwable) { }
        wifiSession = null
        peers.clear()
        main.post { onCount?.invoke(0) }
    }

    private fun log(msg: String) {
        main.post { onLog?.invoke(msg) }
    }

    companion object {
        private const val SERVICE = "meshchat"
    }
}
