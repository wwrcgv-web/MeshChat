package com.example.meshchat

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Partage l'APK de Samchat par le menu Android (Bluetooth, Nearby Share, etc.), sans internet. */
object ShareApp {
    /** Copie l'APK installé dans le cache, puis ouvre le menu de partage. Retourne false si ça échoue. */
    fun share(context: Context): Boolean {
        return try {
            val source = File(context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir)
            val dir = File(context.cacheDir, "partage").apply { mkdirs() }
            val target = File(dir, "Samchat.apk")
            source.copyTo(target, overwrite = true)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Partager Samchat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            false
        }
    }
}
