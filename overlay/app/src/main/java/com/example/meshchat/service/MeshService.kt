package com.example.meshchat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.meshchat.MeshState
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.wifi.WifiAwareDiscovery

/** Service de premier plan : garde MeshChat actif (Bluetooth, Wi-Fi Aware, réserve) même écran éteint. */
class MeshService : Service() {
    private var manager: BleMeshManager? = null
    private var wifi: WifiAwareDiscovery? = null

    override fun onCreate() {
        super.onCreate()
        // La mémoire de l'app reçoit les messages dès la création du service, écran fermé ou non
        MeshState.attach(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "MeshChat", NotificationManager.IMPORTANCE_LOW)
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

        // Un seul gestionnaire et une seule découverte Wi-Fi : elles ne sont pas recréées si l'écran relance le service
        if (manager == null) {
            val m = BleMeshManager(this).also { it.startGattServer(); it.startAdvertising() }
            manager = m
            startWifiAware(m)
        }
        return START_STICKY
    }

    /** Wi-Fi Aware tourne ici, dans le service : il continue écran fermé, comme le Bluetooth. */
    private fun startWifiAware(m: BleMeshManager) {
        val discovery = WifiAwareDiscovery(applicationContext)
        wifi = discovery
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
            .setContentTitle("MeshChat actif")
            .setContentText("Service Bluetooth en cours")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        wifi?.stop()
        wifi = null
        manager?.stop()
        manager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.meshchat.START"
        private const val CHANNEL_ID = "meshchat_service"
        private const val NOTIF_ID = 1
    }
}
