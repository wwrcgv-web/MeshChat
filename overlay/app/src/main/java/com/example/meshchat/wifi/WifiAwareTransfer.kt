package com.example.meshchat.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.os.Build
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Transfert des photos, vocaux, fichiers et vidéos par Wi-Fi Aware.
 * Chaque téléphone ouvre un serveur TCP, puis garde un lien de données avec chaque voisin trouvé.
 * Trame envoyée : drapeau public/privé (1 octet) + taille (4 octets) + contenu.
 * Le receveur répond 1 octet (1) quand il a tout reçu : l'envoyeur sait alors que c'est arrivé.
 */
@SuppressLint("MissingPermission")
class WifiAwareTransfer(
    private val context: Context,
    private val discovery: WifiAwareDiscovery,
    private val log: (String) -> Unit
) {
    /** Lien de données avec un voisin : réseau, adresse et port de ce voisin. */
    private class Link {
        @Volatile var network: Network? = null
        @Volatile var addr: Inet6Address? = null
        @Volatile var port: Int = 0
        var callback: ConnectivityManager.NetworkCallback? = null

        fun ready(): Boolean = network != null && addr != null && port > 0
    }

    private val cm: ConnectivityManager by lazy { context.getSystemService(ConnectivityManager::class.java) }
    private val io = Executors.newCachedThreadPool()
    private val links = ConcurrentHashMap<String, Link>()
    private var server: ServerSocket? = null

    @Volatile private var running = false

    /** Port du serveur de ce téléphone, ou 0 si le serveur n'est pas démarré. */
    @Volatile var serverPort: Int = 0
        private set

    /** Ouvre le serveur d'écoute. [onFrame] reçoit chaque contenu entier : drapeau et données. */
    fun start(onFrame: (Int, ByteArray) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            log("Wi-Fi Aware transfert : Android 13 requis")
            return
        }
        try {
            val s = ServerSocket(0)
            server = s
            serverPort = s.localPort
            running = true
            io.execute {
                while (running) {
                    try {
                        val client = s.accept()
                        io.execute { receive(client, onFrame) }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
            log("Wi-Fi Aware transfert : écoute sur le port $serverPort")
        } catch (e: Exception) {
            log("Wi-Fi Aware transfert : serveur impossible (${e.javaClass.simpleName})")
        }
    }

    /** Lit une trame entrante, répond « reçu », puis transmet le contenu. */
    private fun receive(client: Socket, onFrame: (Int, ByteArray) -> Unit) {
        try {
            client.use { c ->
                val input = DataInputStream(c.getInputStream().buffered())
                val flag = input.readUnsignedByte()
                val size = input.readInt()
                if (size <= 0 || size > MAX_BYTES) return
                val payload = ByteArray(size)
                input.readFully(payload)
                c.getOutputStream().apply { write(1); flush() }
                onFrame(flag, payload)
            }
        } catch (e: Exception) {
            log("Wi-Fi Aware : réception échouée (${e.javaClass.simpleName})")
        }
    }

    /** Ouvre un lien de données avec un voisin trouvé, une seule fois. */
    fun ensureLink(id: String) {
        if (serverPort == 0 || links.containsKey(id)) return
        val (session, handle) = discovery.handleFor(id) ?: return
        val spec = try {
            WifiAwareNetworkSpecifier.Builder(session, handle)
                .setPort(serverPort)
                .setTransportProtocol(TCP)
                .build()
        } catch (e: Exception) {
            log("Wi-Fi Aware lien : spécification impossible (${e.javaClass.simpleName})")
            return
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()
        val link = Link()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                val addr = info.peerIpv6Addr ?: return
                link.network = network
                link.addr = addr
                link.port = info.port
                log("Wi-Fi Aware : lien prêt avec ${id.take(8)}")
            }

            override fun onLost(network: Network) {
                dropLink(id)
                log("Wi-Fi Aware : lien perdu avec ${id.take(8)}")
            }

            override fun onUnavailable() {
                dropLink(id)
                log("Wi-Fi Aware : lien refusé avec ${id.take(8)}")
            }
        }
        link.callback = callback
        links[id] = link
        try {
            cm.requestNetwork(request, callback)
        } catch (e: Exception) {
            dropLink(id)
            log("Wi-Fi Aware lien : demande refusée (${e.javaClass.simpleName})")
        }
    }

    private fun dropLink(id: String) {
        val link = links.remove(id) ?: return
        val callback = link.callback ?: return
        try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) { /* déjà fermé */ }
    }

    /** Envoie les octets à un voisin, et attend son accusé. */
    private fun sendTo(id: String, link: Link, flag: Int, payload: ByteArray) {
        val network = link.network ?: return
        val addr = link.addr ?: return
        try {
            Socket().use { s ->
                network.bindSocket(s)
                s.connect(InetSocketAddress(addr, link.port), CONNECT_MS)
                s.soTimeout = READ_MS
                val out = DataOutputStream(s.getOutputStream().buffered())
                out.writeByte(flag)
                out.writeInt(payload.size)
                out.write(payload)
                out.flush()
                val ack = s.getInputStream().read()
                if (ack == 1) log("Envoyé à ${id.take(8)} (Wi-Fi)")
                else log("Échec envoi Wi-Fi à ${id.take(8)}")
            }
        } catch (e: Exception) {
            log("Échec envoi Wi-Fi à ${id.take(8)} (${e.javaClass.simpleName})")
        }
    }

    /**
     * Envoie à un voisin (ou à tous si [toPeerId] est null).
     * Retourne le nombre de voisins joignables tout de suite. 0 = repli Bluetooth.
     * L'envoi lui-même se fait en arrière-plan.
     */
    fun sendBlob(flag: Int, payload: ByteArray, toPeerId: String?): Int {
        val ids = if (toPeerId == null) links.keys.toList() else listOf(toPeerId)
        val ready = ids.mapNotNull { id -> links[id]?.takeIf { it.ready() }?.let { Pair(id, it) } }
        if (ready.isEmpty()) return 0
        log("Envoi du fichier (Wi-Fi) : ${payload.size} octets vers ${ready.size} pair(s)")
        ready.forEach { (id, link) -> io.execute { sendTo(id, link, flag, payload) } }
        return ready.size
    }

    /** Ferme le serveur et tous les liens. */
    fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) { /* déjà fermé */ }
        server = null
        serverPort = 0
        links.keys.toList().forEach { dropLink(it) }
        io.shutdownNow()
    }

    companion object {
        private const val TCP = 6
        private const val CONNECT_MS = 10_000
        private const val READ_MS = 60_000
        private const val MAX_BYTES = 64 * 1024 * 1024
    }
}
