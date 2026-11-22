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
import androidx.core.content.ContextCompat
import com.example.meshchat.MeshState
import com.example.meshchat.RelayPoll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Groupe commun aux deux notifications (maillage local et relais Internet) : Android les regroupe. */
internal const val SAMCHAT_NOTIF_GROUP = "samchat_services"

/**
 * Service de premier plan du relais Internet.
 *
 * Il ne touche pas au Bluetooth : la relève, la réception et les accusés continuent même quand
 * [MeshService] (BLE, Wi-Fi) est arrêté. Un seul service fait tourner [RelayPoll], pour ne jamais
 * relever deux fois la même boîte.
 */
class RelayService : Service() {

    private val scope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Mémoire de l'app : historique et annuaire, même si le service Bluetooth n'existe pas
        MeshState.attach(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Samchat · relais Internet", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        RelayPoll.start(scope, this) { result ->
            when (result) {
                is RelayPoll.RelayResult.Text -> MeshState.addRelayReceived(result.senderId, result.text)
                is RelayPoll.RelayResult.File -> MeshState.addRelayFile(result.senderId, result.type, result.data)
                is RelayPoll.RelayResult.Ack -> MeshState.confirmerRelais(result.msgId)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Appel obligatoire dans les 5 secondes après startForegroundService
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val pending = PendingIntent.getActivity(
            this, 0, openApp, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Samchat · Internet")
            .setContentText("Relève du relais en cours")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pending)
            .setGroup(SAMCHAT_NOTIF_GROUP)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        instance = null
        RelayPoll.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.meshchat.RELAY_START"
        const val ACTION_STOP = "com.example.meshchat.RELAY_STOP"
        private const val CHANNEL_ID = "meshchat_relay"
        private const val NOTIF_ID = 2

        /** Le service en cours, null s'il est arrêté. */
        @Volatile var instance: RelayService? = null

        /**
         * Démarre le relais Internet. À appeler depuis un écran visible : Android 12+ refuse un
         * service de premier plan lancé depuis l'arrière-plan. Un refus n'interrompt pas l'app,
         * on réessaiera au prochain passage au premier plan.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RelayService::class.java).setAction(ACTION_START)
                )
            } catch (e: Exception) {
                // Démarrage refusé (arrière-plan ou service en cours d'arrêt) : sans gravité
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }
    }
}
