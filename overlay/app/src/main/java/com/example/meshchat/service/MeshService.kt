package com.example.meshchat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.meshchat.MeshState
import com.example.meshchat.ble.BleMeshManager
import com.example.meshchat.wifi.WifiAwareDiscovery
import com.example.meshchat.wifi.WifiAwareTransfer
import com.example.meshchat.wifi.WifiDirectTransfer

/**
 * Service de premier plan : garde Samchat actif (Bluetooth, Wi-Fi Aware, Wi-Fi Direct, transferts, réserve)
 * même écran éteint.
 */
class MeshService : Service() {
    private var manager: BleMeshManager? = null
    private var wifi: WifiAwareDiscovery? = null
    private var transfer: WifiAwareTransfer? = null
    @Volatile private var direct: WifiDirectTransfer? = null

    private val prefs: SharedPreferences by lazy { getSharedPreferences("meshchat", Context.MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())

    /**
     * Suit l'interrupteur Wi-Fi Direct de l'écran : il est branché tout de suite, sans relancer le service.
     * Gardé dans un champ, car Android ne retient cet écouteur que par une référence forte.
     */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_WIFI_DIRECT) setWifiDirectEnabled(prefs.getBoolean(PREF_WIFI_DIRECT, false))
    }

    /**
     * Suit l'interrupteur Bluetooth du téléphone. Quand on l'éteint, Android coupe l'annonce, la recherche
     * et le serveur sans prévenir l'application. Au retour du Bluetooth, tout est relancé.
     */
    private val bluetoothWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onBluetoothOff()
                BluetoothAdapter.STATE_ON -> onBluetoothOn()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // La mémoire de l'app reçoit les messages dès la création du service, écran fermé ou non
        MeshState.attach(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Samchat", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        ContextCompat.registerReceiver(
            this,
            bluetoothWatcher,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Appel obligatoire dans les 5 secondes après startForegroundService, même si l'intent est vide
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        // Un seul gestionnaire : il n'est pas recréé si l'écran relance le service.
        // Les interrupteurs radio enregistrés sont respectés au démarrage.
        if (manager == null) {
            val m = BleMeshManager(this)
            manager = m
            if (m.bleOn) {
                m.startGattServer()
                m.startAdvertising()
            }
            if (wifiAwareWanted()) startWifiAware(m)
            if (wifiDirectWanted()) startWifiDirect(m)
        }
        return START_STICKY
    }

    /** Le Bluetooth du téléphone vient de s'éteindre : on met la radio BLE en pause, sans rien oublier. */
    private fun onBluetoothOff() {
        val m = manager ?: return
        try {
            m.emit("Bluetooth du téléphone éteint")
            m.setBleEnabled(false)
        } catch (e: Exception) {
            // Android a déjà coupé la radio : rien de plus à faire
        }
    }

    /** Le Bluetooth du téléphone est de nouveau allumé : on relance annonce, recherche et serveur. */
    private fun onBluetoothOn() {
        val m = manager ?: return
        m.emit("Bluetooth du téléphone allumé")
        // Laisse au Bluetooth le temps de finir de démarrer
        main.postDelayed({
            if (manager !== m) return@postDelayed
            // Respecte l'interrupteur « Bluetooth BLE » de l'écran : s'il est coupé, on ne relance rien
            if (!prefs.getBoolean(BleMeshManager.PREF_BLE, true)) return@postDelayed
            try {
                // Éteint puis rallume : remet à zéro ce qu'Android a coupé pendant l'arrêt
                m.setBleEnabled(false)
                m.setBleEnabled(true)
            } catch (e: Exception) {
                m.emit("Relance Bluetooth impossible : ${e.message}")
            }
        }, BLUETOOTH_SETTLE_MS)
    }

    private fun wifiAwareWanted(): Boolean = prefs.getBoolean(PREF_WIFI_AWARE, true)

    private fun wifiDirectWanted(): Boolean = prefs.getBoolean(PREF_WIFI_DIRECT, false)

    /** Interrupteur Wi-Fi Aware, appelé depuis l'écran : coupe ou relance la découverte et les transferts. */
    fun setWifiAwareEnabled(on: Boolean) {
        val m = manager ?: return
        if (on) {
            if (wifi == null) startWifiAware(m)
        } else {
            val oldTransfer = transfer
            val oldWifi = wifi
            transfer = null
            wifi = null
            updateWifiSender()
            oldTransfer?.stop()
            oldWifi?.stop()
            MeshState.wifiPeers = 0
        }
    }

    /** Interrupteur Wi-Fi Direct, appelé depuis l'écran : coupe ou lance le groupe, la découverte et les transferts. */
    fun setWifiDirectEnabled(on: Boolean) {
        val m = manager ?: return
        if (on) {
            if (direct == null) startWifiDirect(m)
        } else {
            val old = direct ?: return
            direct = null
            updateWifiSender()
            old.stop()
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
        updateWifiSender()
        discovery.onPeerFound = { id -> tr.ensureLink(id) }
        val myHex = m.myId.joinToString("") { "%02x".format(it) }
        discovery.start(myHex, { MeshState.wifiPeers = it }, { MeshState.addJournal(it) })
    }

    /** Wi-Fi Direct tourne ici : découverte, invitations et liens de transfert. */
    private fun startWifiDirect(m: BleMeshManager) {
        val myHex = m.myId.joinToString("") { "%02x".format(it) }
        val d = WifiDirectTransfer(applicationContext, myHex) { line -> BleMeshManager.instance?.emit(line) }
        if (d.start { flag, payload -> BleMeshManager.instance?.onWifiBlob(flag, payload) }) {
            direct = d
        }
        updateWifiSender()
    }

    /**
     * Chemin d'envoi par Wi-Fi : Wi-Fi Aware d'abord, puis Wi-Fi Direct.
     * Si aucun des deux ne tourne, le Bluetooth seul est utilisé.
     */
    private fun updateWifiSender() {
        val aware = transfer
        val wd = direct
        if (aware == null && wd == null) {
            BleMeshManager.wifiSender = null
            return
        }
        BleMeshManager.wifiSender = { flag, payload, to ->
            val viaAware = aware?.sendBlob(flag, payload, to) ?: 0
            if (viaAware > 0) viaAware else wd?.sendBlob(flag, payload, to) ?: 0
        }
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
        main.removeCallbacksAndMessages(null)
        try { unregisterReceiver(bluetoothWatcher) } catch (e: Exception) { }
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        BleMeshManager.wifiSender = null
        val wd = direct
        direct = null
        wd?.stop()
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
        /** Préférence de l'interrupteur Wi-Fi Direct. Doit rester identique à celle de l'écran. */
        const val PREF_WIFI_DIRECT = "radio_wifi_direct"
        /** Délai après le rallumage du Bluetooth, avant de relancer la radio BLE. */
        private const val BLUETOOTH_SETTLE_MS = 1_500L
        private const val CHANNEL_ID = "meshchat_service"
        private const val NOTIF_ID = 1
        /** Le service en cours, pour que l'écran puisse agir sur les interrupteurs. Null si le service est arrêté. */
        @Volatile var instance: MeshService? = null
    }
}
