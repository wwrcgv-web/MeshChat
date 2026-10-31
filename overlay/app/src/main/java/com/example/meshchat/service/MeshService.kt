package com.example.meshchat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.meshchat.MeshState
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.wifi.WifiAwareDiscovery
import com.example.meshchat.wifi.WifiAwareTransfer

/** Service de premier plan : garde Samchat actif (Bluetooth, Wi-Fi Aware, transferts, réserve) même écran éteint. */
class MeshService : Service() {
    private var manager: BleMeshManager? = null
    private var wifi: WifiAwareDiscovery? = null
    private var transfer: WifiAwareTransfer? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // La mémoire de l'app reçoit les messages dès la création du service, écran fermé ou non
        MeshState.attach(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Samchat", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Appel obligatoire dans les 5 secondes après startForegroundService, même si l'intent est vide (redémarrage système)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        // Un seul gestionnaire : il n'est pas recréé si l'écran relance le service.
        // Les interrupteurs radio enregistrés (Bluetooth BLE, Wi-Fi Aware) sont respectés au démarrage.
        if (manager == null) {
            val m = BleMeshManager(this)
            manager = m
            if (m.bleOn) {
                m.startGattServer()
                m.startAdvertising()
            }
            if (wifiAwareWanted()) startWifiAware(m)
        }
        return START_STICKY
    }

    /** Vrai si l'interrupteur Wi-Fi Aware est activé dans les réglages enregistrés. */
    private fun wifiAwareWanted(): Boolean =
        getSharedPreferences("meshchat", Context.MODE_PRIVATE).getBoolean(PREF_WIFI_AWARE, true)

    /** Interrupteur Wi-Fi Aware, appelé depuis l'écran : coupe ou relance la découverte et les transferts. */
    fun setWifiAwareEnabled(on: Boolean) {
        val m = manager ?: return
        if (on) {
            if (wifi == null) startWifiAware(m)
        } else {
            BleMeshManager.wifiSender = null
            transfer?.stop()
            transfer = null
            wifi?.stop()
            wifi = null
            MeshState.wifiPeers = 0
        }
    }

    /** Wi-Fi Aware tourne ici : découverte, liens de données et transferts continuent écran fermé. */
    private fun startWifiAware(m: BleMeshManager) {
        val discovery = WifiAwareDiscovery(applicationContext)
        val tr = WifiAwareTransfer(applicationContext, discovery) { line -> BleMeshManager.instance?.emit(line) }
        wifi = discovery
        transfer = tr
        // Le serveur d'écoute doit exister avant les liens, pour pouvoir annoncer son port
        tr.start { flag, payload -> BleMeshManager.instance?.onWifiBlob(flag, payload) }
        BleMeshManager.wifiSender = { flag, payload, to -> tr.sendBlob(flag, payload, to) }
        discovery.onPeerFound = { id -> tr.ensureLink(id) }
        val myHex = m.myId.joinToString("") { "%02x".format(it) }
        discovery.start(myHex, { MeshState.wifiPeers = it }, { MeshState.addJournal(it) })
    }

    private fun buildNotification(): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val pending = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Samchat actif")
            .setContentText("Service Bluetooth en cours")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        instance = null
        BleMeshManager.wifiSender = null
        transfer?.stop()
        transfer = null
        wifi?.stop()
        wifi = null
        manager?.stop()
        manager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.meshchat.START"
        /** Préférence de l'interrupteur Wi-Fi Aware. Doit rester identique à celle de l'écran. */
        const val PREF_WIFI_AWARE = "radio_wifi_aware"
        private const val CHANNEL_ID = "meshchat_service"
        private const val NOTIF_ID = 1
        /** Le service en cours, pour que l'écran puisse agir sur les interrupteurs. Null si le service est arrêté. */
        @Volatile var instance: MeshService? = null
    }
}
