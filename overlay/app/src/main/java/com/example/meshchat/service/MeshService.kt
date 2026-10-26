package com.example.meshchat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.example.meshchat.ble.BleMeshManager

class MeshService : Service() {
    private var manager: BleMeshManager? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "MeshChat", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val notification: Notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MeshChat actif").setContentText("Service Bluetooth en cours").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build()
                else Notification.Builder(this).setContentTitle("MeshChat actif").setContentText("Service Bluetooth en cours").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).build()
            startForeground(1, notification)

            // Arrête l'ancienne instance avant d'en démarrer une nouvelle
            manager?.stop()
            manager = BleMeshManager(this).also { it.startGattServer(); it.startAdvertising() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        manager?.stop()
        manager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.meshchat.START"
        private const val CHANNEL_ID = "meshchat_service"
    }
}
