package com.example.meshchat.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.example.meshchat.ProfileStore
import com.example.meshchat.RelayConfig
import com.example.meshchat.crypto.CryptoEngine
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey

/**
 * Un téléphone trouvé : son identifiant MeshChat (16 caractères hexadécimaux)
 * et son nom d'utilisateur (vide si on ne le connaît pas encore).
 */
data class MeshPeer(val id: String, val name: String = "")

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun Int.toBytes4(): ByteArray =
    byteArrayOf((this shr 24).toByte(), (this shr 16).toByte(), (this shr 8).toByte(), this.toByte())
private fun ByteArray.toInt4(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

@SuppressLint("MissingPermission")
class BleMeshManager(private val context: Context) {

    /** Suivi d'un envoi vers plusieurs pairs : « Parti » si au moins un a reçu, sinon « Non envoyé ». */
    private class SendGroup(val msgId: String, val total: Int) {
        @Volatile var done = 0
        @Volatile var ok = 0
    }

    /** Une connexion à un pair, avec les trames à lui envoyer. */
    private class BleJob(val device: BluetoothDevice, val frames: List<ByteArray>, val group: SendGroup?)

    /** Résultat d'une tentative de connexion. */
    private class Attempt(val ok: Boolean, val reason: String)

    /** Une tâche de la file Bluetooth. Le live (priorité 0) passe avant les fichiers et messages (priorité 1). */
    private class Task(
        val priority: Int,
        val seq: Long,
        val live: Boolean,
        val body: () -> Unit
    ) : Runnable, Comparable<Task> {
        override fun run() = body()
        override fun compareTo(other: Task): Int =
            if (priority != other.priority) priority.compareTo(other.priority) else seq.compareTo(other.seq)
    }

    /** Un morceau de voix en cours de réception, rassemblé à partir de ses parties. */
    private class LiveAssembly(total: Int, val createdAt: Long) {
        val parts = arrayOfNulls<ByteArray>(total)
    }

    /** Un profil en cours de réception, rassemblé à partir de ses morceaux. */
    private class ProfileAssembly(total: Int, val createdAt: Long) {
        val parts = arrayOfNulls<ByteArray>(total)
    }

    /** Un téléphone vu récemment, gardé même après extinction du Bluetooth, pour lui dire « je suis là ». */
    private class LastKnown(val device: BluetoothDevice, val seenAt: Long)

    /** Fichier privé gardé par ce téléphone jusqu'à confirmation de réception ou expiration. */
    private class Holding(
        val tag: ByteArray,
        val frames: MutableList<ByteArray>,
        val expiresAt: Long
    )

    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val serviceUuid = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid = UUID.fromString("0000feee-0000-1000-8000-00805f9b34fb")
    private var server: BluetoothGattServer? = null
    private var advertising = false
    @Volatile private var scanning = false
    @Volatile private var stopped = false

    private val bleExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(16, Comparator<Runnable> { a, b -> (a as Task).compareTo(b as Task) })
    )
    private val taskSeq = AtomicLong(0)
    private val pendingJobs = AtomicInteger(0)

    private val liveAssemblies = ConcurrentHashMap<Int, LiveAssembly>()
    private val lastLiveSeq = ConcurrentHashMap<String, Int>()

    /** Profils en cours de réception, par « expéditeur:version ». */
    private val profileAssemblies = ConcurrentHashMap<String, ProfileAssembly>()

    /** Téléphones vus récemment, par identifiant. Non effacé à l'extinction du Bluetooth. */
    private val lastKnown = ConcurrentHashMap<String, LastKnown>()

    /** Fichiers privés gardés par ce téléphone, par identifiant de fichier. Protégé par synchronized(holdings). */
    private val holdings = LinkedHashMap<String, Holding>()

    /**
     * Profil complet (tous ses morceaux) en attente pour chaque contact absent : un seul par contact,
     * le plus récent remplace l'ancien. Protégé par synchronized(pendingProfiles).
     */
    private val pendingProfiles = HashMap<String, List<ByteArray>>()

    private val prefs = context.getSharedPreferences("meshchat", Context.MODE_PRIVATE)

    private val peers = ConcurrentHashMap<String, BluetoothDevice>()
    private val peerIds = ConcurrentHashMap<String, String>()
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val peerKeys = ConcurrentHashMap<String, PublicKey>()
    private val peerNames = ConcurrentHashMap<String, String>()
    private val seenIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val seenSecret: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val seenFragments: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val assemblies = ConcurrentHashMap<String, MeshChunker.Assembly>()
    private val pendingRead = ConcurrentHashMap<String, MutableList<String>>()
    private val queue = MeshQueue()
    private val store = MeshStore()
    private val main = Handler(Looper.getMainLooper())

    private val sweeper = object : Runnable {
        override fun run() {
            if (stopped) return
            sweepPeers()
            main.postDelayed(this, SWEEP_MS)
        }
    }

    @Volatile var lastSentId: String? = null

    val myId: ByteArray = loadOrCreateId()

    private val keyPair: KeyPair = loadOrCreateKeyPair()

    @Volatile var bleOn: Boolean = true
        private set

    init {
        instance = this
        bleOn = prefs.getBoolean(PREF_BLE, true)
        loadPeerKeys()
    }

    private val longRangeSupported: Boolean
        get() = adapter?.isLeCodedPhySupported == true

    val myName: String
        get() = prefs.getString("my_name", "") ?: ""

    fun setMyName(name: String) {
        prefs.edit().putString("my_name", name.trim().take(MAX_NAME_CHARS)).apply()
    }

    private fun loadOrCreateId(): ByteArray {
        val saved = prefs.getString("my_id", null)
        if (saved != null && saved.length == MeshPacket.ID_SIZE * 2) {
            return saved.hexToBytes()
        }
        val fresh = MeshPacket.newPeerId()
        prefs.edit().putString("my_id", fresh.toHex()).apply()
        return fresh
    }

    private fun loadOrCreateKeyPair(): KeyPair {
        val pubHex = prefs.getString("my_pub", null)
        val privHex = prefs.getString("my_priv", null)
        if (pubHex != null && privHex != null) {
            try {
                val factory = KeyFactory.getInstance("EC")
                val pub = factory.generatePublic(X509EncodedKeySpec(pubHex.hexToBytes()))
                val priv = factory.generatePrivate(PKCS8EncodedKeySpec(privHex.hexToBytes()))
                return KeyPair(pub, priv)
            } catch (e: Exception) {
                Log.w(TAG, "Clés illisibles, nouvelle paire créée", e)
            }
        }
        val fresh = CryptoEngine.newKeyPair()
        prefs.edit()
            .putString("my_pub", fresh.public.encoded.toHex())
            .putString("my_priv", fresh.private.encoded.toHex())
            .apply()
        return fresh
    }

    private fun loadPeerKeys() {
        try {
            val factory = KeyFactory.getInstance("EC")
            prefs.all.forEach { (key, value) ->
                if (value !is String) return@forEach
                if (key.startsWith("name_")) {
                    peerNames[key.removePrefix("name_")] = value
                } else if (key.startsWith("peer_")) {
                    try {
                        peerKeys[key.removePrefix("peer_")] = factory.generatePublic(X509EncodedKeySpec(value.hexToBytes()))
                    } catch (e: Exception) {
                        Log.w(TAG, "Clé de pair illisible ignorée : $key")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Impossible de relire les clés des pairs", e)
        }
    }

    fun emit(msg: String) {
        Log.i(TAG, msg)
        main.post { listener?.invoke(msg) }
    }

    private fun publishPeers() {
        val list = peerIds.values.distinct().sorted().map { MeshPeer(it, peerNames[it] ?: "") }
        main.post { peersListener?.invoke(list) }
    }

    private fun sweepPeers() {
        val now = System.currentTimeMillis()
        var removed = false
        for (mac in peers.keys.toList()) {
            if (now - (lastSeen[mac] ?: 0L) > PEER_TIMEOUT_MS) {
                peers.remove(mac)
                peerIds.remove(mac)
                lastSeen.remove(mac)
                removed = true
            }
        }
        if (removed) publishPeers()
    }

    fun knownContacts(): List<MeshPeer> =
        peerKeys.keys.sorted().map { MeshPeer(it, peerNames[it] ?: "") }

    fun sharedKeyWith(peerId: String): SecretKey? {
        val theirs = peerKeys[peerId] ?: return null
        return CryptoEngine.sharedKey(keyPair.private, theirs)
    }

    /** Secret partagé brut avec un contact (en octets), pour calculer les boîtes quotidiennes. */
    private fun sharedSecretBytes(peerId: String): ByteArray? = sharedKeyWith(peerId)?.encoded

    /** Étiquettes de boîte à attendre de ce contact (aujourd'hui et hier). Vide si la clé n'est pas connue. */
    private fun inboxTagsFor(peerId: String): List<ByteArray> {
        val secret = sharedSecretBytes(peerId) ?: return emptyList()
        return MeshStore.boxTagsToCheck(secret)
    }

    /** Étiquette à mettre sur un message envoyé à ce contact : la boîte du jour, calculée avec le secret partagé. */
    private fun outgoingTagFor(peerId: String): ByteArray? {
        val secret = sharedSecretBytes(peerId) ?: return null
        return MeshStore.boxTag(secret, MeshStore.currentDay())
    }

    /**
     * Retourne l'identifiant du contact dont une boîte (aujourd'hui ou hier) correspond à l'étiquette reçue,
     * ou null si aucune boîte ne correspond.
     */
    private fun contactForTag(tag: ByteArray): String? {
        for (peerId in peerKeys.keys) {
            if (inboxTagsFor(peerId).any { it.contentEquals(tag) }) return peerId
        }
        return null
    }

    private fun openForMe(cipher: ByteArray): Pair<String, ByteArray>? {
        for (entry in peerKeys.entries) {
            val key = CryptoEngine.sharedKey(keyPair.private, entry.value)
            val plain = CryptoEngine.decrypt(cipher, key)
            if (plain != null) return Pair(entry.key, plain)
        }
        return null
    }

    /**
     * Interrupteur Bluetooth BLE.
     * Désactivé : on prévient les pairs en vue (« je pars »), puis on coupe annonce, scan et serveur.
     * Activé : on relance annonce, scan et serveur, puis on prévient les pairs récents (« je suis là »).
     */
    fun setBleEnabled(on: Boolean) {
        if (on == bleOn) return
        bleOn = on
        if (on) {
            if (stopped) return
            emit("Bluetooth BLE activé")
            startGattServer()
            startAdvertising()
            sendPeerHereNow()
        } else {
            emit("Bluetooth BLE désactivé")
            sendPeerLeaveNow()
            pauseScan()
            try {
                if (advertising) adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
            } catch (e: SecurityException) {
                Log.w(TAG, "Arrêt annonce impossible", e)
            }
            advertising = false
            try { server?.close() } catch (e: SecurityException) { Log.w(TAG, "Fermeture serveur impossible", e) }
            server = null
            peers.clear()
            peerIds.clear()
            lastSeen.clear()
            publishPeers()
        }
    }

    /**
     * Envoie « je pars » aux pairs en vue, dans un fil séparé et avec un délai court.
     * Best effort : si le Bluetooth est coupé brutalement, rien ne part et le délai de 30 s prend le relais.
     */
    private fun sendPeerLeaveNow() {
        val targets = peers.values.toList()
        if (targets.isEmpty()) return
        val frame = byteArrayOf(TYPE_PEER_LEAVE) + myId
        Thread {
            targets.forEach { device ->
                try {
                    connectAndWrite(device, listOf(frame), PEER_NOTICE_TIMEOUT_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Trame « je pars » non envoyée", e)
                }
            }
        }.start()
        emit("Trame « je pars » envoyée à ${targets.size} pair(s)")
    }

    /** Envoie « je suis là » aux téléphones vus récemment, pour qu'ils te remettent en liste tout de suite. */
    private fun sendPeerHereNow() {
        val now = System.currentTimeMillis()
        lastKnown.entries.removeIf { now - it.value.seenAt > LAST_KNOWN_MS }
        val targets = lastKnown.values.map { it.device }
        if (targets.isEmpty()) return
        val frame = byteArrayOf(TYPE_PEER_HERE) + myId
        targets.forEach { enqueue(it, listOf(frame)) }
        emit("Trame « je suis là » envoyée à ${targets.size} pair(s)")
    }

    fun startAdvertising() {
        emit("Mon identifiant : ${myId.toHex().take(8)}")
        emit("Clés mémorisées : ${peerKeys.size}")
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run { emit("Annonce Bluetooth indisponible"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(serviceUuid)).setIncludeDeviceName(false).build()
        val response = AdvertiseData.Builder().addManufacturerData(COMPANY_ID, myId).setIncludeDeviceName(false).build()
        try { advertiser.startAdvertising(settings, data, response, callback) } catch (e: SecurityException) { emit("Permission Bluetooth manquante") }
        startScan()
        main.removeCallbacks(sweeper)
        main.postDelayed(sweeper, SWEEP_MS)
    }

    fun startScan() {
        if (scanning) return
        if (adapter?.bluetoothLeScanner == null) { emit("Scan indisponible"); return }
        if (startScanQuietly()) emit("Recherche de pairs...")
    }

    private fun startScanQuietly(): Boolean {
        if (scanning || stopped || !bleOn) return false
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            true
        } catch (e: SecurityException) {
            emit("Permission scan manquante")
            false
        }
    }

    private fun pauseScan() {
        if (!scanning) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (e: SecurityException) { Log.w(TAG, "Pause du scan impossible", e) }
        scanning = false
    }

    fun startGattServer() {
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            server = manager.openGattServer(context, gattCallback)
            val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                characteristicUuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_READ
            )
            service.addCharacteristic(characteristic)
            server?.addService(service)
        } catch (e: SecurityException) { emit("Permission serveur manquante") }
    }

    /**
     * Envoie un message texte. Public : relayé. Privé : chiffré, étiqueté par la boîte du jour,
     * relayé sans que les intermédiaires puissent le lire.
     */
    fun sendMessage(text: String, toPeerId: String? = null): Int {
        lastSentId = null
        val bytes = text.toByteArray(Charsets.UTF_8)

        if (toPeerId == null) {
            if (bytes.size > MAX_TEXT_BYTES) {
                emit("Message trop long (200 caractères maximum)")
                return 0
            }
            val packet = MeshPacket(UUID.randomUUID(), myId, ByteArray(MeshPacket.ID_SIZE), RelayConfig.maxRelays(context), text)
            seenIds.add(packet.id)
            val targets = peers.values.toList()
            if (targets.isEmpty()) {
                queue.add(packet)
                emit("Aucun pair : message mis en attente (10 min)")
                return 1
            }
            val frame = textFrame(packet)
            targets.forEach { sendTo(it, frame) }
            return targets.size
        }

        if (bytes.size > MAX_PRIVATE_BYTES) {
            emit("Message privé trop long (180 caractères maximum)")
            return 0
        }
        val key = sharedKeyWith(toPeerId) ?: run {
            emit("Clé de ce pair pas encore reçue : message non envoyé")
            return 0
        }
        val tag = outgoingTagFor(toPeerId) ?: return 0
        val targets = peers.values.toList()
        val msgId = ByteArray(MeshPacket.ID_SIZE).also { SecureRandom().nextBytes(it) }
        seenSecret.add(msgId.toHex())
        lastSentId = msgId.toHex()
        val cipher = CryptoEngine.encrypt(bytes, key)
        store.add(msgId, tag, cipher)
        if (targets.isEmpty()) {
            emit("En attente ${msgId.toHex()}")
            emit("Aucun pair : message privé gardé en attente (10 min)")
            return 1
        }
        val frame = byteArrayOf(TYPE_SECRET, RelayConfig.maxRelays(context).toByte()) + msgId + tag + cipher
        val group = SendGroup(msgId.toHex(), targets.size)
        targets.forEach { enqueue(it, listOf(frame), group) }
        return targets.size
    }

    fun sendLive(chunk: ByteArray, toPeerId: String) {
        if (stopped) return
        val key = sharedKeyWith(toPeerId) ?: run {
            emit("Live : clé de ce pair pas encore reçue")
            return
        }
        val cipher = CryptoEngine.encrypt(chunk, key)
        val seq = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
        val tag = outgoingTagFor(toPeerId) ?: return
        dropStaleLive()
        try {
            bleExecutor.execute(Task(PRIO_LIVE, taskSeq.incrementAndGet(), live = true) {
                try {
                    runLive(toPeerId, tag, seq, cipher)
                } catch (e: Exception) {
                    Log.w(TAG, "Live interrompu", e)
                }
            })
        } catch (e: RejectedExecutionException) {
            // File fermée
        }
    }

    private fun runLive(toPeerId: String, tag: ByteArray, seq: Int, cipher: ByteArray) {
        val viaWifi = wifiSender?.invoke(FLAG_LIVE, tag + seq.toBytes4() + cipher, toPeerId) ?: 0
        if (viaWifi > 0) return
        if (!bleOn) return

        val device = peers.entries.firstOrNull { peerIds[it.key] == toPeerId }?.value ?: return
        val total = (cipher.size + LIVE_PART_BYTES - 1) / LIVE_PART_BYTES
        if (total > 255) {
            emit("Live : morceau trop long")
            return
        }
        val frames = (0 until total).map { i ->
            val from = i * LIVE_PART_BYTES
            val to = minOf(cipher.size, from + LIVE_PART_BYTES)
            byteArrayOf(TYPE_LIVE) + tag + seq.toBytes4() + byteArrayOf(i.toByte(), total.toByte()) + cipher.copyOfRange(from, to)
        }
        val result = connectAndWrite(device, frames, LIVE_TIMEOUT_MS)
        if (!result.ok) emit("Live : morceau non envoyé par Bluetooth (${result.reason})")
    }

    /**
     * Envoie une photo, un vocal ou un fichier. Public : aux voisins présents.
     * Privé : étiqueté par la boîte du jour. Gardé jusqu'à confirmation de réception.
     */
    fun sendBlob(bytes: ByteArray, toPeerId: String? = null): Int {
        lastSentId = null
        val blobId = ByteArray(MeshPacket.ID_SIZE).also { SecureRandom().nextBytes(it) }
        val tag = if (toPeerId != null) outgoingTagFor(toPeerId) ?: return 0 else ByteArray(0)
        val payload = if (toPeerId == null) {
            bytes
        } else {
            val key = sharedKeyWith(toPeerId) ?: run {
                emit("Clé de ce pair pas encore reçue : fichier non envoyé")
                return 0
            }
            CryptoEngine.encrypt(blobId + bytes, key)
        }
        val flag = if (toPeerId == null) FLAG_PUBLIC else FLAG_PRIVATE
        if (toPeerId != null) lastSentId = blobId.toHex()

        val viaWifi = wifiSender?.invoke(flag, payload, toPeerId) ?: 0
        if (viaWifi > 0) return viaWifi

        if (payload.size > MAX_BLE_BLOB) {
            emit("Fichier refusé : ${bytes.size / 1024} Ko, limite Bluetooth ${MAX_BLE_BLOB / 1000} Ko")
            return 0
        }
        val targets = peers.values.toList()
        val ttl = if (toPeerId == null) 1 else RelayConfig.maxRelays(context)
        val fragments = MeshChunker.split(MeshChunker.newContentId(), payload)
        val frames = fragments.map { chunk ->
            if (toPeerId == null) byteArrayOf(TYPE_FRAGMENT, ttl.toByte(), flag.toByte()) + chunk
            else byteArrayOf(TYPE_FRAGMENT, ttl.toByte(), flag.toByte()) + tag + blobId + chunk
        }

        if (toPeerId == null) {
            if (targets.isEmpty()) { emit("Aucun pair : fichier non envoyé"); return 0 }
            emit("Envoi du fichier : ${bytes.size} octets en ${frames.size} fragments")
            targets.forEach { enqueue(it, frames) }
            return targets.size
        }

        val target: String = toPeerId
        val msgId = blobId.toHex()
        val destination = peers.entries.filter { peerIds[it.key] == target }.map { it.value }
        val others = peers.entries.filter { peerIds[it.key] != target }.map { it.value }

        keepBlob(msgId, tag, frames, announce = destination.isEmpty())

        if (destination.isEmpty()) {
            others.forEach { enqueue(it, frames) }
            if (others.isEmpty()) emit("Aucun pair : fichier gardé en attente")
            else emit("Fichier confié à ${others.size} voisin(s), en attente du destinataire")
            return 1
        }

        val group = SendGroup(msgId, destination.size)
        emit("Envoi du fichier : ${bytes.size} octets en ${frames.size} fragments")
        destination.forEach { enqueue(it, frames, group) }
        others.forEach { enqueue(it, frames) }
        return destination.size + others.size
    }

    private fun keepBlob(msgId: String, tag: ByteArray, frames: List<ByteArray>, announce: Boolean) {
        synchronized(holdings) {
            purgeHoldingsLocked()
            if (holdings.size >= MAX_HOLDINGS && !holdings.containsKey(msgId)) {
                holdings.remove(holdings.keys.first())
            }
            holdings[msgId] = Holding(tag, frames.toMutableList(), System.currentTimeMillis() + RelayConfig.keepMs(context))
        }
        if (announce) emit("En attente $msgId")
    }

    private fun holdFragment(blobHex: String, tag: ByteArray, frame: ByteArray) {
        synchronized(holdings) {
            purgeHoldingsLocked()
            val existing = holdings[blobHex]
            if (existing != null) {
                existing.frames.add(frame)
                return
            }
            if (holdings.size >= MAX_HOLDINGS) return
            holdings[blobHex] = Holding(tag, mutableListOf(frame), System.currentTimeMillis() + RelayConfig.keepMs(context))
        }
    }

    private fun purgeHoldingsLocked() {
        val now = System.currentTimeMillis()
        holdings.entries.removeIf { it.value.expiresAt <= now }
    }

    private fun removeHolding(blobHex: String) {
        val removed = synchronized(holdings) { holdings.remove(blobHex) != null }
        if (removed) emit("Livré : copie gardée supprimée ${blobHex.take(8)}")
    }

    /** Fichiers gardés dont l'étiquette correspond à ce contact (aujourd'hui ou hier). */
    private fun holdingsFor(peerId: String): List<List<ByteArray>> {
        val tags = inboxTagsFor(peerId)
        val outTag = outgoingTagFor(peerId)
        val found = synchronized(holdings) {
            purgeHoldingsLocked()
            holdings.values.filter { h -> (outTag != null && h.tag.contentEquals(outTag)) || tags.any { h.tag.contentEquals(it) } }
                .map { it.frames.toList() }
        }
        if (found.isNotEmpty()) emit("Envoi de ${found.size} fichier(s) gardé(s) à ${peerId.take(8)}")
        return found
    }

    fun markRead(peerId: String) {
        val list = pendingRead.remove(peerId) ?: return
        list.forEach { sendAck(peerId, it.hexToBytes(), KIND_READ) }
    }

    fun onWifiBlob(flag: Int, payload: ByteArray) {
        when (flag) {
            FLAG_LIVE -> handleWifiLive(payload)
            FLAG_PUBLIC -> deliverBlob(payload, null)
            else -> {
                val opened = openForMe(payload) ?: return
                acceptPrivateBlob(opened.first, opened.second)
            }
        }
    }

    private fun acceptPrivateBlob(sender: String, plain: ByteArray) {
        if (plain.size <= MeshPacket.ID_SIZE) return
        val blobId = plain.copyOfRange(0, MeshPacket.ID_SIZE)
        val content = plain.copyOfRange(MeshPacket.ID_SIZE, plain.size)
        deliverBlob(content, sender)
        sendAck(sender, blobId, KIND_RECEIVED)
    }

    private fun deliverBlob(bytes: ByteArray, from: String?) {
        emit("Fichier reçu : ${bytes.size} octets")
        val receive = blobListener
        if (receive != null) main.post { receive(bytes, from) }
    }

    private fun textFrame(packet: MeshPacket): ByteArray = byteArrayOf(TYPE_TEXT) + packet.encode()

    private fun keyFrame(): ByteArray {
        val nameBytes = myName.take(MAX_NAME_CHARS).toByteArray(Charsets.UTF_8)
        return byteArrayOf(TYPE_KEY) + myId + byteArrayOf(nameBytes.size.toByte()) + nameBytes +
            CryptoEngine.encodePublic(keyPair.public)
    }

    /**
     * Découpe le profil complet (statut et photo, chiffrés avec le secret partagé de ce contact)
     * en morceaux. Trame : type (1) + mon identifiant (8) + version (4) + rang (1) + nombre (1) + données.
     */
    private fun profileFrames(peerId: String, version: Int, plain: ByteArray): List<ByteArray>? {
        val key = sharedKeyWith(peerId) ?: return null
        val cipher = CryptoEngine.encrypt(plain, key)
        val total = (cipher.size + PROFILE_PART_BYTES - 1) / PROFILE_PART_BYTES
        if (total <= 0 || total > 255) return null
        val versionBytes = version.toBytes4()
        return (0 until total).map { i ->
            val from = i * PROFILE_PART_BYTES
            val to = minOf(cipher.size, from + PROFILE_PART_BYTES)
            byteArrayOf(TYPE_PROFILE_PART) + myId + versionBytes +
                byteArrayOf(i.toByte(), total.toByte()) + cipher.copyOfRange(from, to)
        }
    }

    /**
     * Envoie mon profil (statut, version et photo) à chaque contact. Ceux à portée le reçoivent tout de suite ;
     * les absents gardent une seule version, la plus récente remplace l'ancienne.
     */
    fun broadcastProfile() {
        val version = ProfileStore.myVersion(context)
        val status = ProfileStore.myStatus(context)
        val avatar = ProfileStore.myAvatar(context)
        val plain = ProfileStore.encodeWithAvatar(version, status, avatar)
        val nowHere = peers.entries.toList()
        for (peerId in peerKeys.keys) {
            val frames = profileFrames(peerId, version, plain) ?: continue
            val device = nowHere.firstOrNull { peerIds[it.key] == peerId }?.value
            if (device != null) {
                enqueue(device, frames)
            } else {
                synchronized(pendingProfiles) { pendingProfiles[peerId] = frames }
            }
        }
        emit("Profil envoyé (version $version, ${if (avatar != null) "avec photo" else "sans photo"})")
    }

    /**
     * Reçoit un morceau de profil. Quand tous les morceaux sont là, le profil est déchiffré et accepté
     * seulement s'il est plus récent que celui qu'on a déjà.
     */
    private fun handleProfilePart(body: ByteArray) {
        val idSize = MeshPacket.ID_SIZE
        val header = idSize + 4 + 2
        if (body.size <= header) return
        val senderId = body.copyOfRange(0, idSize).toHex()
        val version = body.toInt4(idSize)
        val index = body[idSize + 4].toInt() and 0xFF
        val total = body[idSize + 5].toInt() and 0xFF
        if (total == 0 || index >= total) return
        if (version <= ProfileStore.versionOf(context, senderId)) return
        val secret = sharedKeyWith(senderId) ?: return
        val part = body.copyOfRange(header, body.size)

        val now = System.currentTimeMillis()
        profileAssemblies.entries.removeIf { now - it.value.createdAt > PROFILE_ASSEMBLY_MS }
        val assemblyKey = "$senderId:$version"
        val assembly = profileAssemblies.getOrPut(assemblyKey) { ProfileAssembly(total, now) }
        if (assembly.parts.size != total) return
        assembly.parts[index] = part
        if (assembly.parts.any { it == null }) return
        profileAssemblies.remove(assemblyKey)

        val cipher = assembly.parts.fold(ByteArray(0)) { acc, p -> acc + p!! }
        val plain = CryptoEngine.decrypt(cipher, secret) ?: return
        val decoded = ProfileStore.decodeWithAvatar(plain) ?: return
        if (decoded.first != version) return
        if (ProfileStore.acceptFromPeer(context, senderId, version, decoded.second, decoded.third)) {
            emit("Profil reçu de ${(peerNames[senderId] ?: senderId).take(8)} (version $version)")
        }
    }

    /** Ancien format de profil (statut seul), gardé pour compatibilité avec les anciennes versions. */
    private fun handleProfile(body: ByteArray) {
        val idSize = MeshPacket.ID_SIZE
        if (body.size <= idSize) return
        val senderId = body.copyOfRange(0, idSize).toHex()
        val key = sharedKeyWith(senderId) ?: return
        val plain = CryptoEngine.decrypt(body.copyOfRange(idSize, body.size), key) ?: return
        val decoded = ProfileStore.decode(plain) ?: return
        if (ProfileStore.acceptFromPeer(context, senderId, decoded.first, decoded.second)) {
            emit("Profil reçu de ${senderId.take(8)} (version ${decoded.first})")
        }
    }

    /** « Je pars » : retire immédiatement ce téléphone de la liste des pairs à portée. */
    private fun handlePeerLeave(body: ByteArray) {
        val idSize = MeshPacket.ID_SIZE
        if (body.size < idSize) return
        val id = body.copyOfRange(0, idSize).toHex()
        val macs = peerIds.filter { it.value == id }.keys.toList()
        if (macs.isEmpty()) return
        macs.forEach {
            peers.remove(it)
            peerIds.remove(it)
            lastSeen.remove(it)
        }
        emit("Pair parti : ${(peerNames[id] ?: id).take(8)}")
        publishPeers()
    }

    /** « Je suis là » : remet immédiatement ce téléphone dans la liste, et lance l'échange de clé. */
    private fun handlePeerHere(mac: String, body: ByteArray) {
        val idSize = MeshPacket.ID_SIZE
        if (body.size < idSize) return
        val id = body.copyOfRange(0, idSize).toHex()
        if (id == myId.toHex()) return
        val device = adapter?.getRemoteDevice(mac) ?: return
        peers[mac] = device
        lastSeen[mac] = System.currentTimeMillis()
        onPeerSeen(device, id)
    }

    private fun sendTo(device: BluetoothDevice, frame: ByteArray) = enqueue(device, listOf(frame))

    private fun enqueue(device: BluetoothDevice, frames: List<ByteArray>, group: SendGroup? = null) {
        if (frames.isEmpty() || stopped) return
        if (!bleOn) {
            group?.let { finishGroup(it, false) }
            return
        }
        pendingJobs.incrementAndGet()
        pauseScan()
        try {
            bleExecutor.execute(Task(PRIO_NORMAL, taskSeq.incrementAndGet(), live = false) {
                runJob(BleJob(device, frames, group))
            })
        } catch (e: RejectedExecutionException) {
            finishJob()
        }
    }

    private fun dropStaleLive() {
        val iterator = bleExecutor.queue.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (task is Task && task.live) iterator.remove()
        }
    }

    private fun runJob(job: BleJob) {
        try {
            val ok = if (!bleOn) false else try {
                sendWithRetry(job.device, job.frames)
            } catch (e: Exception) {
                Log.w(TAG, "Envoi interrompu", e)
                false
            }
            job.group?.let { finishGroup(it, ok) }
        } finally {
            finishJob()
        }
    }

    private fun finishGroup(g: SendGroup, ok: Boolean) {
        g.done++
        if (ok) g.ok++
        if (g.done == g.total) {
            if (g.ok > 0) emit("Parti ${g.msgId}") else emit("Non envoyé ${g.msgId}")
        }
    }

    private fun finishJob() {
        if (pendingJobs.decrementAndGet() == 0) startScanQuietly()
    }

    private fun fileTimeoutMs(frameCount: Int): Long = TIMEOUT_BASE_MS + frameCount * TIMEOUT_PER_FRAME_MS

    private fun sendWithRetry(device: BluetoothDevice, frames: List<ByteArray>): Boolean {
        for (attempt in 1..MAX_ATTEMPTS) {
            val result = connectAndWrite(device, frames, fileTimeoutMs(frames.size))
            if (result.ok) {
                emit("Envoyé à ${device.address}")
                return true
            }
            emit("Échec envoi à ${device.address} (${result.reason}), essai $attempt/$MAX_ATTEMPTS")
            if (attempt < MAX_ATTEMPTS) Thread.sleep(RETRY_PAUSE_MS * attempt)
        }
        return false
    }

    private fun connectAndWrite(device: BluetoothDevice, frames: List<ByteArray>, timeoutMs: Long): Attempt {
        val finished = CountDownLatch(1)
        val outcome = AtomicReference<Attempt?>(null)
        fun finish(result: Attempt) {
            if (outcome.compareAndSet(null, result)) finished.countDown()
        }

        var index = 0
        var characteristic: BluetoothGattCharacteristic? = null

        fun writeNext(gatt: BluetoothGatt) {
            val ch = characteristic
            if (ch == null) {
                finish(Attempt(false, "service introuvable"))
                gatt.disconnect()
                return
            }
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = frames[index]
            if (!gatt.writeCharacteristic(ch)) {
                finish(Attempt(false, "écriture refusée"))
                gatt.disconnect()
            }
        }

        val connectCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (gatt?.requestMtu(247) != true) gatt?.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    finish(Attempt(false, "code $status"))
                    gatt?.close()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                gatt?.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (gatt == null) return
                characteristic = gatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
                writeNext(gatt)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt?, ch: BluetoothGattCharacteristic?, status: Int) {
                if (gatt == null) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    finish(Attempt(false, "écriture code $status"))
                    gatt.disconnect()
                    return
                }
                index++
                if (index < frames.size) {
                    writeNext(gatt)
                } else {
                    finish(Attempt(true, ""))
                    gatt.disconnect()
                }
            }
        }

        val phy = if (prefs.getBoolean(PREF_LONG_RANGE, true) && longRangeSupported) {
            BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK
        } else {
            BluetoothDevice.PHY_LE_1M_MASK
        }
        val handle: BluetoothGatt? = try {
            device.connectGatt(context, false, connectCallback, BluetoothDevice.TRANSPORT_LE, phy)
        } catch (e: SecurityException) {
            return Attempt(false, "permission refusée")
        }

        if (!finished.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            finish(Attempt(false, "délai dépassé"))
            handle?.disconnect()
            handle?.close()
        }
        return outcome.get() ?: Attempt(false, "inconnu")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val r = result ?: return
            val mac = r.device.address
            lastSeen[mac] = System.currentTimeMillis()
            peers.put(mac, r.device)
            val idBytes = r.scanRecord?.getManufacturerSpecificData(COMPANY_ID)
            if (idBytes != null && idBytes.size == MeshPacket.ID_SIZE) {
                onPeerSeen(r.device, idBytes.toHex())
            }
        }
        override fun onScanFailed(errorCode: Int) { emit("Échec scan : $errorCode") }
    }

    /**
     * Un pair est vu (par scan ou par trame « je suis là »). Si c'est une nouvelle rencontre,
     * on échange les clés, les messages gardés, les profils et les fichiers en attente.
     */
    private fun onPeerSeen(device: BluetoothDevice, id: String) {
        val mac = device.address
        lastKnown[id] = LastKnown(device, System.currentTimeMillis())
        if (peerIds.put(mac, id) == null) {
            peerIds.filter { it.value == id && it.key != mac }.keys.toList().forEach {
                peers.remove(it)
                peerIds.remove(it)
                lastSeen.remove(it)
            }
            emit("Pair trouvé : ${(peerNames[id] ?: id).take(8)}")
            publishPeers()
            val frames = listOf(keyFrame()) + storedFramesFor(id) + queuedFrames()
            enqueue(device, frames)
            holdingsFor(id).forEach { blobFrames -> enqueue(device, blobFrames) }
            synchronized(pendingProfiles) { pendingProfiles.remove(id) }?.let { enqueue(device, it) }
        }
    }

    private fun queuedFrames(): List<ByteArray> {
        val pending = queue.drain()
        if (pending.isEmpty()) return emptyList()
        emit("Envoi de ${pending.size} message(s) en attente")
        return pending.map { textFrame(it) }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (characteristic?.uuid == characteristicUuid && value != null) handleIncoming(device?.address ?: "", value)
            if (responseNeeded && device != null) {
                try { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) } catch (e: SecurityException) { Log.e(TAG, "Réponse GATT impossible", e) }
            }
        }
    }

    private fun handleIncoming(fromAddress: String, value: ByteArray) {
        if (value.isEmpty()) return
        when (value[0]) {
            TYPE_TEXT -> handleText(fromAddress, value.copyOfRange(1, value.size))
            TYPE_FRAGMENT -> handleFragmentFrame(fromAddress, value.copyOfRange(1, value.size))
            TYPE_KEY -> handleKey(value.copyOfRange(1, value.size))
            TYPE_SECRET -> handleSecret(fromAddress, value.copyOfRange(1, value.size))
            TYPE_ACK -> handleAck(fromAddress, value.copyOfRange(1, value.size))
            TYPE_LIVE -> handleLive(value.copyOfRange(1, value.size))
            TYPE_PROFILE -> handleProfile(value.copyOfRange(1, value.size))
            TYPE_PROFILE_PART -> handleProfilePart(value.copyOfRange(1, value.size))
            TYPE_PEER_LEAVE -> handlePeerLeave(value.copyOfRange(1, value.size))
            TYPE_PEER_HERE -> handlePeerHere(fromAddress, value.copyOfRange(1, value.size))
            else -> emit("Message reçu inconnu")
        }
    }

    private fun handleFragmentFrame(fromAddress: String, body: ByteArray) {
        if (body.size < 2) { emit("Fragment mal formé"); return }
        val ttl = body[0].toInt() and 0xFF
        val flag = body[1].toInt() and 0xFF
        if (flag == FLAG_PRIVATE) {
            val header = 2 + MeshStore.TAG_SIZE + MeshPacket.ID_SIZE
            if (body.size <= header) { emit("Fragment mal formé"); return }
            val tag = body.copyOfRange(2, 2 + MeshStore.TAG_SIZE)
            val blobId = body.copyOfRange(2 + MeshStore.TAG_SIZE, header)
            handleFragment(fromAddress, ttl, flag, tag, blobId, body.copyOfRange(header, body.size))
        } else {
            handleFragment(fromAddress, ttl, flag, null, null, body.copyOfRange(2, body.size))
        }
    }

    private fun handleLive(body: ByteArray) {
        val tagSize = MeshStore.TAG_SIZE
        val header = tagSize + 4 + 2
        if (body.size <= header) return
        val tag = body.copyOfRange(0, tagSize)
        if (contactForTag(tag) == null) return
        val seq = body.toInt4(tagSize)
        val index = body[tagSize + 4].toInt() and 0xFF
        val total = body[tagSize + 5].toInt() and 0xFF
        if (total == 0 || index >= total) return
        val part = body.copyOfRange(header, body.size)

        val now = System.currentTimeMillis()
        liveAssemblies.entries.removeIf { now - it.value.createdAt > LIVE_ASSEMBLY_MS }
        val assembly = liveAssemblies.getOrPut(seq) { LiveAssembly(total, now) }
        if (assembly.parts.size != total) return
        assembly.parts[index] = part
        if (assembly.parts.any { it == null }) return
        liveAssemblies.remove(seq)

        val cipher = assembly.parts.fold(ByteArray(0)) { acc, p -> acc + p!! }
        deliverLive(seq, cipher)
    }

    private fun handleWifiLive(body: ByteArray) {
        val tagSize = MeshStore.TAG_SIZE
        val header = tagSize + 4
        if (body.size <= header) return
        val tag = body.copyOfRange(0, tagSize)
        if (contactForTag(tag) == null) return
        val seq = body.toInt4(tagSize)
        deliverLive(seq, body.copyOfRange(header, body.size))
    }

    private fun deliverLive(seq: Int, cipher: ByteArray) {
        val (sender, audio) = openForMe(cipher) ?: return
        if (seq <= (lastLiveSeq[sender] ?: Int.MIN_VALUE)) return
        lastLiveSeq[sender] = seq
        val receive = liveListener ?: return
        main.post { receive(sender, audio) }
    }

    private fun handleKey(body: ByteArray) {
        val idSize = MeshPacket.ID_SIZE
        if (body.size <= idSize + 1) { emit("Clé mal formée"); return }
        val senderId = body.copyOfRange(0, idSize).toHex()
        val nameLen = body[idSize].toInt() and 0xFF
        val nameStart = idSize + 1
        if (body.size <= nameStart + nameLen) { emit("Clé mal formée"); return }
        val name = String(body, nameStart, nameLen, Charsets.UTF_8).trim()
        val publicBytes = body.copyOfRange(nameStart + nameLen, body.size)
        try {
            val key = CryptoEngine.decodePublic(publicBytes)
            peerKeys[senderId] = key
            peerNames[senderId] = name
            prefs.edit()
                .putString("peer_$senderId", publicBytes.toHex())
                .putString("name_$senderId", name)
                .apply()
            publishPeers()
            emit("Clé reçue de ${name.ifBlank { senderId.take(8) }}")
        } catch (e: Exception) {
            emit("Clé invalide reçue de ${senderId.take(8)}")
        }
    }

    /**
     * Message privé chiffré. Le corps : TTL (1) + identifiant (8) + étiquette de boîte (8) + données chiffrées.
     * Si l'étiquette correspond à un de nos contacts et que le déchiffrement réussit, il est pour nous.
     * Sinon, on le garde (étiquette inconnue) et on le relaie avec le fanout.
     */
    private fun handleSecret(fromAddress: String, body: ByteArray) {
        val idSize = MeshPacket.ID_SIZE
        val tagSize = MeshStore.TAG_SIZE
        if (body.size < 1 + idSize + tagSize + 28) { emit("Message privé mal formé"); return }
        val ttl = body[0].toInt() and 0xFF
        val msgId = body.copyOfRange(1, 1 + idSize)
        val tag = body.copyOfRange(1 + idSize, 1 + idSize + tagSize)
        val cipher = body.copyOfRange(1 + idSize + tagSize, body.size)

        if (!seenSecret.add(msgId.toHex())) return

        val opened = openForMe(cipher)
        if (opened != null) {
            emit("Reçu privé de ${opened.first} : ${String(opened.second, Charsets.UTF_8)}")
            pendingRead.getOrPut(opened.first) { mutableListOf() }.add(msgId.toHex())
            sendAck(opened.first, msgId, KIND_RECEIVED)
        } else {
            store.add(msgId, tag, cipher)
        }

        if (ttl > 1) relaySecret(ttl - 1, msgId, tag, cipher, fromAddress)
    }

    /** Relais d'un message privé : fanout limité, choisi au hasard parmi les voisins sauf l'expéditeur. */
    private fun relaySecret(ttl: Int, msgId: ByteArray, tag: ByteArray, cipher: ByteArray, exceptAddress: String) {
        val candidates = peers.values.filter { it.address != exceptAddress }
        if (candidates.isEmpty()) return
        val chosen = RelayConfig.pickNeighbors(candidates, RelayConfig.fanout(context))
        emit("Relais privé vers ${chosen.size} pair(s) (TTL $ttl)")
        val frame = byteArrayOf(TYPE_SECRET, ttl.toByte()) + msgId + tag + cipher
        chosen.forEach { sendTo(it, frame) }
    }

    private fun storedFramesFor(peerId: String): List<ByteArray> {
        val tags = inboxTagsFor(peerId) + listOfNotNull(outgoingTagFor(peerId))
        val items = tags.flatMap { store.forTag(it) }.distinctBy { it.id.toHex() }
        if (items.isNotEmpty()) emit("Livraison de ${items.size} message(s) gardé(s) à ${peerId.take(8)}")
        return items.map { byteArrayOf(TYPE_SECRET, 1.toByte()) + it.id + it.tag + it.cipher }
    }

    /**
     * Accusé chiffré (Reçu ou Lu). Format : ttl (1) + identifiant de l'accusé (8) + identifiant du message
     * confirmé, en clair (8) + données chiffrées.
     */
    private fun sendAck(peerId: String, originalId: ByteArray, kind: Byte) {
        val key = sharedKeyWith(peerId) ?: return
        val ackId = ByteArray(MeshPacket.ID_SIZE).also { SecureRandom().nextBytes(it) }
        seenSecret.add(ackId.toHex())
        val cipher = CryptoEngine.encrypt(byteArrayOf(kind) + originalId, key)
        val frame = byteArrayOf(TYPE_ACK, RelayConfig.maxRelays(context).toByte()) + ackId + originalId + cipher
        peers.values.toList().forEach { sendTo(it, frame) }
    }

    private fun handleAck(fromAddress: String, body: ByteArray) {
        val idSize = MeshPacket.ID_SIZE
        val header = 1 + idSize + idSize
        if (body.size < header + 28) return
        val ttl = body[0].toInt() and 0xFF
        val ackId = body.copyOfRange(1, 1 + idSize)
        val confirmedId = body.copyOfRange(1 + idSize, header)
        val cipher = body.copyOfRange(header, body.size)
        if (!seenSecret.add(ackId.toHex())) return

        removeHolding(confirmedId.toHex())

        val opened = openAck(cipher)
        if (opened != null) {
            val label = if (opened.first == KIND_READ) "lu" else "reçu"
            emit("Statut ${opened.second} $label")
        }

        if (ttl > 1) {
            val candidates = peers.values.filter { it.address != fromAddress }
            val chosen = RelayConfig.pickNeighbors(candidates, RelayConfig.fanout(context))
            val frame = byteArrayOf(TYPE_ACK, (ttl - 1).toByte()) + ackId + confirmedId + cipher
            chosen.forEach { sendTo(it, frame) }
        }
    }

    private fun openAck(cipher: ByteArray): Pair<Byte, String>? {
        for (entry in peerKeys.entries) {
            val key = CryptoEngine.sharedKey(keyPair.private, entry.value)
            val plain = CryptoEngine.decrypt(cipher, key) ?: continue
            if (plain.size == 1 + MeshPacket.ID_SIZE) {
                return Pair(plain[0], plain.copyOfRange(1, plain.size).toHex())
            }
        }
        return null
    }

    private fun handleText(fromAddress: String, body: ByteArray) {
        val packet = MeshPacket.decode(body)
        if (packet == null) { emit("Message reçu mal formé"); return }
        if (!seenIds.add(packet.id)) return
        val isPublic = packet.recipient.all { it == 0.toByte() }
        if (isPublic) emit("Reçu de ${packet.sender.toHex()} : ${packet.text}")
        if (packet.canRelay()) relay(packet.withDecrementedTtl(), exceptAddress = fromAddress)
    }

    private fun handleFragment(
        fromAddress: String,
        ttl: Int,
        flag: Int,
        tag: ByteArray?,
        blobId: ByteArray?,
        chunk: ByteArray
    ) {
        assemblies.entries.removeIf { it.value.isExpired() }
        val (key, total, part) = MeshChunker.parse(chunk) ?: run { emit("Fragment mal formé"); return }

        if (!seenFragments.add("$key:${part.first}")) return
        if (seenFragments.size > MAX_SEEN_FRAGMENTS) seenFragments.clear()

        if (flag == FLAG_PRIVATE && tag != null && blobId != null) {
            val frame = byteArrayOf(TYPE_FRAGMENT, ttl.toByte(), FLAG_PRIVATE.toByte()) + tag + blobId + chunk
            val forMe = contactForTag(tag) != null
            if (!forMe) holdFragment(blobId.toHex(), tag, frame)
            if (ttl > 1) relayPrivateFragment(ttl - 1, tag, blobId, chunk, fromAddress)
        }

        val assembly = assemblies.getOrPut(key) { MeshChunker.Assembly(total) }
        assembly.add(part.first, part.second)
        if (!assembly.isComplete()) return
        assemblies.remove(key)
        val bytes = assembly.assemble()

        if (flag == FLAG_PUBLIC) {
            deliverBlob(bytes, null)
            return
        }

        val opened = openForMe(bytes) ?: return
        acceptPrivateBlob(opened.first, opened.second)
    }

    private fun relayPrivateFragment(ttl: Int, tag: ByteArray, blobId: ByteArray, chunk: ByteArray, exceptAddress: String) {
        val candidates = peers.values.filter { it.address != exceptAddress }
        if (candidates.isEmpty()) return
        val chosen = RelayConfig.pickNeighbors(candidates, RelayConfig.fanout(context))
        emit("Relais fichier privé vers ${chosen.size} pair(s) (TTL $ttl)")
        val frame = byteArrayOf(TYPE_FRAGMENT, ttl.toByte(), FLAG_PRIVATE.toByte()) + tag + blobId + chunk
        chosen.forEach { sendTo(it, frame) }
    }

    private fun relay(packet: MeshPacket, exceptAddress: String) {
        val candidates = peers.values.filter { it.address != exceptAddress }
        if (candidates.isEmpty()) return
        val chosen = RelayConfig.pickNeighbors(candidates, RelayConfig.fanout(context))
        emit("Relais vers ${chosen.size} pair(s) (TTL ${packet.ttl})")
        val frame = textFrame(packet)
        chosen.forEach { sendTo(it, frame) }
    }

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true; emit("Annonce Bluetooth active") }
        override fun onStartFailure(errorCode: Int) { advertising = false; emit("Échec annonce : $errorCode") }
    }

    fun stop() {
        stopped = true
        main.removeCallbacks(sweeper)
        bleExecutor.shutdownNow()
        try { if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (e: SecurityException) { Log.w(TAG, "Arrêt scan impossible", e) }
        try { if (advertising) adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) } catch (e: SecurityException) { Log.w(TAG, "Arrêt annonce impossible", e) }
        try { server?.close() } catch (e: SecurityException) { Log.w(TAG, "Fermeture serveur impossible", e) }
        scanning = false; advertising = false; server = null
        peers.clear()
        peerIds.clear()
        lastSeen.clear()
        assemblies.clear()
        seenFragments.clear()
        pendingRead.clear()
        liveAssemblies.clear()
        profileAssemblies.clear()
        publishPeers()
        instance = null
    }

    companion object {
        private const val TAG = "MeshChatBLE"
        private const val MAX_TEXT_BYTES = 200
        private const val MAX_PRIVATE_BYTES = 180
        private const val MAX_SEEN_FRAGMENTS = 5000
        private const val MAX_NAME_CHARS = 20
        private const val MAX_BLE_BLOB = 250_000
        private const val MAX_HOLDINGS = 30
        private const val PEER_TIMEOUT_MS = 30_000L
        private const val SWEEP_MS = 5_000L
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_PAUSE_MS = 1_500L
        private const val TIMEOUT_BASE_MS = 20_000L
        private const val TIMEOUT_PER_FRAME_MS = 500L
        private const val LIVE_PART_BYTES = 200
        private const val LIVE_TIMEOUT_MS = 6_000L
        private const val LIVE_ASSEMBLY_MS = 5_000L
        /** Délai court pour les trames « je pars » : elles doivent partir avant la coupure. */
        private const val PEER_NOTICE_TIMEOUT_MS = 2_000L
        /** Un téléphone vu il y a moins de ce délai reçoit « je suis là » à la réactivation du Bluetooth. */
        private const val LAST_KNOWN_MS = 10 * 60 * 1000L
        /** Taille d'un morceau de profil, avant l'en-tête de la trame. */
        private const val PROFILE_PART_BYTES = 180
        /** Délai après lequel un profil incomplet est abandonné. */
        private const val PROFILE_ASSEMBLY_MS = 60_000L
        private const val PRIO_LIVE = 0
        private const val PRIO_NORMAL = 1
        private const val TYPE_TEXT: Byte = 1
        private const val TYPE_FRAGMENT: Byte = 2
        private const val TYPE_KEY: Byte = 3
        private const val TYPE_SECRET: Byte = 4
        private const val TYPE_ACK: Byte = 5
        private const val TYPE_LIVE: Byte = 6
        private const val TYPE_PROFILE: Byte = 7
        private const val TYPE_PROFILE_PART: Byte = 8
        /** « Je pars » : le téléphone désactive son Bluetooth. */
        private const val TYPE_PEER_LEAVE: Byte = 9
        /** « Je suis là » : le téléphone réactive son Bluetooth. */
        private const val TYPE_PEER_HERE: Byte = 10
        private const val KIND_RECEIVED: Byte = 1
        private const val KIND_READ: Byte = 2
        private const val FLAG_PUBLIC = 0
        private const val FLAG_PRIVATE = 1
        private const val FLAG_LIVE = 2
        const val PREF_BLE = "radio_ble"
        const val PREF_LONG_RANGE = "radio_long_range"
        const val COMPANY_ID = 0xFFFE
        @Volatile var instance: BleMeshManager? = null
        @Volatile var listener: ((String) -> Unit)? = null
        @Volatile var blobListener: ((ByteArray, String?) -> Unit)? = null
        @Volatile var liveListener: ((String, ByteArray) -> Unit)? = null
        @Volatile var peersListener: ((List<MeshPeer>) -> Unit)? = null
        @Volatile var wifiSender: ((Int, ByteArray, String?) -> Int)? = null
    }
}
