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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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

    /**
     * Un message ou fichier gardé, prêt à être (re)envoyé à un téléphone.
     * [tracked] vrai : c'est mon propre message pour ce téléphone, on suit son état (« Parti »).
     */
    private class Parcel(val id: String, val frames: List<ByteArray>, val tracked: Boolean)

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

    /** Dossier où les fichiers gardés sont sauvegardés (ils survivent à un redémarrage du service). */
    private val holdDir: File = File(context.filesDir, "holdings").apply { mkdirs() }

    /** Fichiers privés gardés par ce téléphone, par identifiant de fichier. Protégé par synchronized(holdings). */
    private val holdings = LinkedHashMap<String, Holding>()

    /** Profil complet en attente pour chaque contact absent : un seul par contact. Protégé par synchronized(pendingProfiles). */
    private val pendingProfiles = HashMap<String, List<ByteArray>>()

    /** Accusés que le destinataire n'a pas pu remettre (l'expéditeur n'était pas en vue). Protégé par synchronized(pendingAcks). */
    private val pendingAcks = HashMap<String, MutableList<ByteArray>>()

    /** Identifiants des messages et fichiers que j'ai envoyés moi-même. */
    private val ownSent: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Identifiants dont la livraison est confirmée : on ne les renvoie plus. */
    private val deliveredIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** « téléphone:message » déjà transmis par ce relais, pour ne pas le renvoyer plusieurs fois. */
    private val forwarded: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Dernier essai d'envoi de chaque message, pour espacer les nouveaux essais. */
    private val lastTry = ConcurrentHashMap<String, Long>()

    /** « Parti » et « Non envoyé » ne sont annoncés qu'une fois par message (le journal reste lisible). */
    private val partiEmitted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val failEmitted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Messages et fichiers que j'ai reçus : identifiant -> expéditeur. Sert à renvoyer l'accusé si un doublon arrive. */
    private val receivedFrom = ConcurrentHashMap<String, String>()
    private val lastAckResend = ConcurrentHashMap<String, Long>()
    @Volatile private var lastRetryAt = 0L

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

    /** Messages reçus de chaque contact, pas encore signalés « Lus ». */
    private val pendingRead = ConcurrentHashMap<String, MutableList<String>>()
    private val queue = MeshQueue()
    private val store = MeshStore(File(context.filesDir, "meshstore.json"))
    private val main = Handler(Looper.getMainLooper())

    private val sweeper = object : Runnable {
        override fun run() {
            if (stopped) return
            sweepPeers()
            val now = System.currentTimeMillis()
            if (now - lastRetryAt >= RETRY_TICK_MS) {
                lastRetryAt = now
                retryPending()
            }
            main.postDelayed(this, SWEEP_MS)
        }
    }

    @Volatile var lastSentId: String? = null

    /**
     * Contact dont la discussion est ouverte à l'écran (null si aucune, ou si l'app n'est pas visible).
     * Un message reçu de ce contact est signalé « Lu » tout de suite.
     */
    @Volatile var openChatPeer: String? = null

    val myId: ByteArray = loadOrCreateId()

    /** Mon identifiant en hexadécimal. */
    val myIdHex: String get() = myId.toHex()

    private val keyPair: KeyPair = loadOrCreateKeyPair()

    @Volatile var bleOn: Boolean = true
        private set

    init {
        instance = this
        bleOn = prefs.getBoolean(PREF_BLE, true)
        loadPeerKeys()
        loadHoldings()
    }

    private val longRangeSupported: Boolean
        get() = adapter?.isLeCodedPhySupported == true

    private val fastPhySupported: Boolean
        get() = adapter?.isLe2MPhySupported == true

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

    /** Étiquette à mettre sur un message envoyé à ce contact : la boîte du jour. */
    private fun outgoingTagFor(peerId: String): ByteArray? {
        val secret = sharedSecretBytes(peerId) ?: return null
        return MeshStore.boxTag(secret, MeshStore.currentDay())
    }

    /** Identifiant du contact dont une boîte (aujourd'hui ou hier) correspond à l'étiquette, ou null. */
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

    // ---------- Fichiers gardés, sauvegardés sur le disque ----------

    private fun holdFile(id: String) = File(holdDir, "$id.bin")

    private fun saveHolding(id: String, h: Holding, own: Boolean) {
        try {
            DataOutputStream(holdFile(id).outputStream().buffered()).use { out ->
                out.writeBoolean(own)
                out.writeLong(h.expiresAt)
                out.writeInt(h.tag.size)
                out.write(h.tag)
                out.writeInt(h.frames.size)
                h.frames.forEach { f ->
                    out.writeInt(f.size)
                    out.write(f)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fichier gardé non sauvegardé", e)
        }
    }

    private fun loadHoldings() {
        val now = System.currentTimeMillis()
        holdDir.listFiles()?.forEach { f ->
            try {
                DataInputStream(f.inputStream().buffered()).use { input ->
                    val own = input.readBoolean()
                    val expires = input.readLong()
                    if (expires <= now) {
                        f.delete()
                        return@use
                    }
                    val tag = ByteArray(input.readInt())
                    input.readFully(tag)
                    val count = input.readInt()
                    val frames = ArrayList<ByteArray>()
                    for (n in 0 until count) {
                        val frame = ByteArray(input.readInt())
                        input.readFully(frame)
                        frames.add(frame)
                    }
                    val id = f.nameWithoutExtension
                    holdings[id] = Holding(tag, frames, expires)
                    if (own) ownSent.add(id)
                }
            } catch (e: Exception) {
                f.delete()
            }
        }
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

    /** Envoie « je pars » aux pairs en vue. Best effort : sinon le délai de 30 s prend le relais. */
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

    /** Envoie « je suis là » aux téléphones vus récemment. */
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
        val hex = msgId.toHex()
        seenSecret.add(hex)
        ownSent.add(hex)
        lastTry[hex] = System.currentTimeMillis()
        lastSentId = hex
        val cipher = CryptoEngine.encrypt(bytes, key)
        store.add(msgId, tag, cipher)
        if (targets.isEmpty()) {
            emit("En attente $hex")
            emit("Aucun pair : message privé gardé en attente")
            return 1
        }
        val frame = byteArrayOf(TYPE_SECRET, RelayConfig.maxRelays(context).toByte()) + msgId + tag + cipher
        val group = SendGroup(hex, targets.size)
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
     * Privé : étiqueté par la boîte du jour, gardé (sur le disque) jusqu'à confirmation de réception.
     * Si le Wi-Fi échoue, le fichier reste gardé et repart par Bluetooth au nouvel essai.
     */
    fun sendBlob(bytes: ByteArray, toPeerId: String? = null): Int {
        lastSentId = null
        val blobId = ByteArray(MeshPacket.ID_SIZE).also { SecureRandom().nextBytes(it) }
        val msgId = blobId.toHex()
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
        if (toPeerId != null) {
            lastSentId = msgId
            ownSent.add(msgId)
            lastTry[msgId] = System.currentTimeMillis()
        }

        // Trop gros pour le Bluetooth : seulement par Wi-Fi
        if (payload.size > MAX_BLE_BLOB) {
            val viaWifi = wifiSender?.invoke(flag, payload, toPeerId) ?: 0
            if (viaWifi > 0) return viaWifi
            emit("Fichier refusé : ${bytes.size / 1024} Ko, limite Bluetooth ${MAX_BLE_BLOB / 1000} Ko")
            return 0
        }

        val ttl = if (toPeerId == null) 1 else RelayConfig.maxRelays(context)
        val fragments = MeshChunker.split(MeshChunker.newContentId(), payload)
        val frames = fragments.map { chunk ->
            if (toPeerId == null) byteArrayOf(TYPE_FRAGMENT, ttl.toByte(), flag.toByte()) + chunk
            else byteArrayOf(TYPE_FRAGMENT, ttl.toByte(), flag.toByte()) + tag + blobId + chunk
        }

        // Privé : le fichier est gardé avant l'envoi, pour pouvoir être renvoyé si le Wi-Fi échoue
        if (toPeerId != null) keepBlob(msgId, tag, frames)

        val viaWifi = wifiSender?.invoke(flag, payload, toPeerId) ?: 0
        if (viaWifi > 0) return viaWifi

        val targets = peers.values.toList()
        if (toPeerId == null) {
            if (targets.isEmpty()) { emit("Aucun pair : fichier non envoyé"); return 0 }
            emit("Envoi du fichier : ${bytes.size} octets en ${frames.size} fragments")
            targets.forEach { enqueue(it, frames) }
            return targets.size
        }

        val destination = peers.entries.filter { peerIds[it.key] == toPeerId }.map { it.value }
        val others = peers.entries.filter { peerIds[it.key] != toPeerId }.map { it.value }

        if (destination.isEmpty()) {
            emit("En attente $msgId")
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

    // ---------- Éléments gardés ----------

    /** Garde mon propre fichier privé (sauvegardé sur le disque) jusqu'à la confirmation de réception. */
    private fun keepBlob(msgId: String, tag: ByteArray, frames: List<ByteArray>) {
        val holding = Holding(tag, frames.toMutableList(), System.currentTimeMillis() + RelayConfig.keepMs(context))
        synchronized(holdings) {
            purgeHoldingsLocked()
            if (holdings.size >= MAX_HOLDINGS && !holdings.containsKey(msgId)) {
                val oldest = holdings.keys.first()
                holdings.remove(oldest)
                holdFile(oldest).delete()
            }
            holdings[msgId] = holding
        }
        saveHolding(msgId, holding, own = true)
    }

    /** Un relais garde un fragment privé destiné à quelqu'un d'autre (en mémoire seulement). */
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
        val iterator = holdings.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.expiresAt <= now) {
                iterator.remove()
                holdFile(entry.key).delete()
            }
        }
    }

    private fun removeHolding(blobHex: String) {
        val removed = synchronized(holdings) { holdings.remove(blobHex) != null }
        holdFile(blobHex).delete()
        if (removed) emit("Livré : copie gardée supprimée ${blobHex.take(8)}")
    }

    /**
     * Messages et fichiers gardés à envoyer à ce téléphone.
     * - ceux qui lui sont destinés (l'étiquette correspond à sa boîte) : toujours à la rencontre,
     *   et, en mode [retry], seulement si le dernier essai est assez ancien ;
     * - les autres (que ce téléphone garde comme relais) : transmis une seule fois par téléphone rencontré.
     * Les trames renvoyées ont un TTL de 1 : le téléphone qui les reçoit les garde, sans les propager en chaîne.
     */
    private fun parcelsFor(peerId: String, retry: Boolean): List<Parcel> {
        val tags = inboxTagsFor(peerId) + listOfNotNull(outgoingTagFor(peerId))
        val now = System.currentTimeMillis()
        val result = ArrayList<Parcel>()
        var forwardedCount = 0
        if (forwarded.size > MAX_SEEN_FRAGMENTS) forwarded.clear()

        // 0 : ignorer, 1 : destiné à ce téléphone, 2 : à transmettre comme relais
        fun decide(hex: String, tag: ByteArray): Int {
            if (hex in deliveredIds) return 0
            if (tags.any { it.contentEquals(tag) }) {
                if (retry && now - (lastTry[hex] ?: 0L) < RETRY_MIN_MS) return 0
                lastTry[hex] = now
                return 1
            }
            if (retry || forwardedCount >= MAX_FORWARD_PER_MEETING) return 0
            if (!forwarded.add("$peerId:$hex")) return 0
            forwardedCount++
            return 2
        }

        for (item in store.all()) {
            val hex = item.id.toHex()
            val kind = decide(hex, item.tag)
            if (kind == 0) continue
            val frame = byteArrayOf(TYPE_SECRET, 1.toByte()) + item.id + item.tag + item.cipher
            result.add(Parcel(hex, listOf(frame), kind == 1 && hex in ownSent))
        }

        val snapshot = synchronized(holdings) {
            purgeHoldingsLocked()
            holdings.entries.map { Triple(it.key, it.value.tag, it.value.frames.toList()) }
        }
        for ((hex, tag, frames) in snapshot) {
            val kind = decide(hex, tag)
            if (kind == 0) continue
            val lowTtl = frames.map { f -> f.copyOf().also { if (it.size > 2) it[1] = 1 } }
            result.add(Parcel(hex, lowTtl, kind == 1 && hex in ownSent))
        }

        if (result.isNotEmpty() && !retry) emit("Renvoi de ${result.size} élément(s) gardé(s) à ${peerId.take(8)}")
        return result
    }

    /** Nouvel essai pour les messages et fichiers non confirmés, tant que leur destinataire est à portée. */
    private fun retryPending() {
        if (!bleOn || stopped || pendingJobs.get() > 0) return
        val here = peers.entries.toList()
        for (id in here.mapNotNull { peerIds[it.key] }.distinct()) {
            if (peerKeys[id] == null) continue
            val device = here.firstOrNull { peerIds[it.key] == id }?.value ?: continue
            parcelsFor(id, retry = true).forEach { enqueue(device, it.frames, if (it.tracked) SendGroup(it.id, 1) else null) }
            synchronized(pendingAcks) { pendingAcks.remove(id) }?.let { enqueue(device, it) }
        }
    }

    // ---------- Accusés « Lu » ----------

    /** Signale « Lu » tous les messages (textes, photos, vocaux) reçus de ce contact et pas encore lus. */
    fun markRead(peerId: String) {
        val list = pendingRead.remove(peerId) ?: return
        list.forEach { sendAck(peerId, it.hexToBytes(), KIND_READ) }
    }

    /** Retient qu'un message de ce contact est à signaler « Lu », et le fait tout de suite si sa discussion est ouverte. */
    private fun noteUnread(peerId: String, hex: String) {
        pendingRead.getOrPut(peerId) { CopyOnWriteArrayList() }.add(hex)
        if (openChatPeer == peerId) markRead(peerId)
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
        val hex = blobId.toHex()
        if (receivedFrom.size > MAX_SEEN_FRAGMENTS) receivedFrom.clear()
        // Déjà reçu : on ne l'affiche pas une seconde fois, mais on renvoie l'accusé
        if (receivedFrom.putIfAbsent(hex, sender) != null) {
            resendAck(sender, hex)
            return
        }
        val content = plain.copyOfRange(MeshPacket.ID_SIZE, plain.size)
        deliverBlob(content, sender)
        sendAck(sender, blobId, KIND_RECEIVED)
        noteUnread(sender, hex)
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

    /** Envoie mon profil à chaque contact. Les absents gardent une seule version, la plus récente. */
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

    /** Reçoit un morceau de profil ; accepté seulement s'il est plus récent que celui qu'on a. */
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

    /** Ancien format de profil (statut seul), gardé pour compatibilité. */
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

    /**
     * « Je suis là » : remet ce téléphone dans la liste. On utilise l'adresse vue lors d'un scan précédent
     * (celle de l'appelant peut être différente et ne permet pas de le joindre). Inconnu : le scan le trouvera.
     */
    private fun handlePeerHere(body: ByteArray) {
        val idSize = MeshPacket.ID_SIZE
        if (body.size < idSize) return
        val id = body.copyOfRange(0, idSize).toHex()
        if (id == myId.toHex()) return
        val device = lastKnown[id]?.device ?: return
        peers[device.address] = device
        lastSeen[device.address] = System.currentTimeMillis()
        onPeerSeen(device, id)
    }

    private fun sendTo(device: BluetoothDevice, frame: ByteArray) = enqueue(device, listOf(frame))

    /**
     * Met une connexion en file. Par défaut le scan reste allumé (l'arrêter à chaque envoi dépasse la limite
     * d'Android, environ 5 démarrages par 30 s). Le réglage « Scan pendant les envois » permet de le couper.
     */
    private fun enqueue(device: BluetoothDevice, frames: List<ByteArray>, group: SendGroup? = null) {
        if (frames.isEmpty() || stopped) return
        if (!bleOn) {
            group?.let { finishGroup(it, false) }
            return
        }
        pendingJobs.incrementAndGet()
        if (!prefs.getBoolean(PREF_SCAN_SEND, true)) pauseScan()
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
            if (g.ok > 0) {
                failEmitted.remove(g.msgId)
                if (partiEmitted.add(g.msgId)) emit("Parti ${g.msgId}")
            } else if (g.msgId !in partiEmitted && failEmitted.add(g.msgId)) {
                emit("Non envoyé ${g.msgId}")
            }
        }
    }

    private fun finishJob() {
        if (pendingJobs.decrementAndGet() == 0) startScanQuietly()
    }

    private fun fileTimeoutMs(frameCount: Int): Long = TIMEOUT_BASE_MS + frameCount * TIMEOUT_PER_FRAME_MS

    private fun sendWithRetry(device: BluetoothDevice, frames: List<ByteArray>): Boolean {
        val bulk = frames.size > BULK_FRAMES
        for (attempt in 1..MAX_ATTEMPTS) {
            val result = connectAndWrite(device, frames, fileTimeoutMs(frames.size), bulk)
            if (result.ok) {
                emit("Envoyé à ${device.address}")
                return true
            }
            emit("Échec envoi à ${device.address} (${result.reason}), essai $attempt/$MAX_ATTEMPTS")
            if (attempt < MAX_ATTEMPTS) Thread.sleep(RETRY_PAUSE_MS * attempt)
        }
        return false
    }

    /**
     * Ouvre une connexion et écrit toutes les trames. [bulk] : envoi volumineux, donc PHY rapide (1M/2M)
     * au lieu de Long Range, qui est bien plus lent. La priorité de connexion « haute » est toujours demandée.
     */
    private fun connectAndWrite(device: BluetoothDevice, frames: List<ByteArray>, timeoutMs: Long, bulk: Boolean = false): Attempt {
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
                    try {
                        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    } catch (e: Exception) {
                        Log.w(TAG, "Priorité de connexion refusée", e)
                    }
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

        val phy = when {
            bulk && fastPhySupported -> BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK
            bulk -> BluetoothDevice.PHY_LE_1M_MASK
            prefs.getBoolean(PREF_LONG_RANGE, true) && longRangeSupported ->
                BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK
            else -> BluetoothDevice.PHY_LE_1M_MASK
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
     * Un pair est vu (par scan ou par trame « je suis là »). Pour une nouvelle rencontre, tout ce qui n'a pas
     * besoin d'un suivi part dans UNE seule connexion : clé, éléments gardés pour relais, messages publics en
     * attente, profil et accusés. Mes propres messages gardés pour ce pair gardent chacun leur connexion,
     * pour que leur statut (« Parti ») reste exact.
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

            val parcels = parcelsFor(id, retry = false)
            val batch = ArrayList<ByteArray>()
            batch.add(keyFrame())
            parcels.filter { !it.tracked }.forEach { batch.addAll(it.frames) }
            batch.addAll(queuedFrames())
            synchronized(pendingProfiles) { pendingProfiles.remove(id) }?.let { batch.addAll(it) }
            synchronized(pendingAcks) { pendingAcks.remove(id) }?.let { batch.addAll(it) }
            enqueue(device, batch)

            parcels.filter { it.tracked }.forEach { enqueue(device, it.frames, SendGroup(it.id, 1)) }
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
            TYPE_PEER_HERE -> handlePeerHere(value.copyOfRange(1, value.size))
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
     * S'il est pour nous : on l'affiche, on envoie « Reçu », et « Lu » si sa discussion est ouverte.
     * Sinon on le garde et on le relaie. Un doublon d'un message déjà reçu renvoie l'accusé.
     */
    private fun handleSecret(fromAddress: String, body: ByteArray) {
        val idSize = MeshPacket.ID_SIZE
        val tagSize = MeshStore.TAG_SIZE
        if (body.size < 1 + idSize + tagSize + 28) { emit("Message privé mal formé"); return }
        val ttl = body[0].toInt() and 0xFF
        val msgId = body.copyOfRange(1, 1 + idSize)
        val tag = body.copyOfRange(1 + idSize, 1 + idSize + tagSize)
        val cipher = body.copyOfRange(1 + idSize + tagSize, body.size)
        val hex = msgId.toHex()

        if (!seenSecret.add(hex)) {
            receivedFrom[hex]?.let { resendAck(it, hex) }
            return
        }

        val opened = openForMe(cipher)
        if (opened != null) {
            if (receivedFrom.size > MAX_SEEN_FRAGMENTS) receivedFrom.clear()
            receivedFrom[hex] = opened.first
            emit("Reçu privé de ${opened.first} : ${String(opened.second, Charsets.UTF_8)}")
            sendAck(opened.first, msgId, KIND_RECEIVED)
            noteUnread(opened.first, hex)
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

    /**
     * Accusé chiffré (Reçu ou Lu). Format : ttl (1) + identifiant de l'accusé (8) + identifiant du message
     * confirmé, en clair (8) + données chiffrées. Si l'expéditeur n'est pas en vue, l'accusé est gardé
     * et remis à la prochaine rencontre.
     */
    private fun sendAck(peerId: String, originalId: ByteArray, kind: Byte) {
        val key = sharedKeyWith(peerId) ?: return
        val ackId = ByteArray(MeshPacket.ID_SIZE).also { SecureRandom().nextBytes(it) }
        seenSecret.add(ackId.toHex())
        val cipher = CryptoEngine.encrypt(byteArrayOf(kind) + originalId, key)
        val frame = byteArrayOf(TYPE_ACK, RelayConfig.maxRelays(context).toByte()) + ackId + originalId + cipher
        val here = peers.entries.toList()
        val hereIds = here.mapNotNull { peerIds[it.key] }.toSet()
        here.forEach { sendTo(it.value, frame) }
        if (peerId !in hereIds) keepAck(peerId, frame)
    }

    private fun keepAck(peerId: String, frame: ByteArray) {
        synchronized(pendingAcks) {
            val list = pendingAcks.getOrPut(peerId) { mutableListOf() }
            list.add(frame)
            while (list.size > MAX_PENDING_ACKS) list.removeAt(0)
        }
    }

    /** Renvoie l'accusé « Reçu » d'un message déjà reçu, au plus une fois toutes les 10 secondes. */
    private fun resendAck(senderId: String, hex: String) {
        val now = System.currentTimeMillis()
        if (now - (lastAckResend[hex] ?: 0L) < ACK_RESEND_MIN_MS) return
        lastAckResend[hex] = now
        sendAck(senderId, hex.hexToBytes(), KIND_RECEIVED)
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

        // Livré : on ne renvoie plus ce message, et les copies gardées ici peuvent disparaître
        val confirmedHex = confirmedId.toHex()
        if (deliveredIds.size > MAX_SEEN_FRAGMENTS) deliveredIds.clear()
        deliveredIds.add(confirmedHex)
        store.remove(confirmedId)
        removeHolding(confirmedHex)

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

        // Fichier déjà reçu : on renvoie l'accusé (le précédent s'est peut-être perdu) et on ignore le reste
        val blobHex = blobId?.toHex()
        if (blobHex != null) {
            val already = receivedFrom[blobHex]
            if (already != null) {
                resendAck(already, blobHex)
                return
            }
        }

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
        /** Au-delà de ce nombre de trames, l'envoi est « volumineux » : PHY rapide au lieu de Long Range. */
        private const val BULK_FRAMES = 4
        private const val LIVE_PART_BYTES = 200
        private const val LIVE_TIMEOUT_MS = 6_000L
        private const val LIVE_ASSEMBLY_MS = 5_000L
        /** Vérification des messages non confirmés : toutes les 30 secondes. */
        private const val RETRY_TICK_MS = 30_000L
        /** Délai minimal entre deux essais pour un même message. */
        private const val RETRY_MIN_MS = 45_000L
        /** Nombre maximal d'éléments gardés qu'un relais transmet à un téléphone qu'il rencontre. */
        private const val MAX_FORWARD_PER_MEETING = 20
        private const val MAX_PENDING_ACKS = 30
        private const val ACK_RESEND_MIN_MS = 10_000L
        private const val PEER_NOTICE_TIMEOUT_MS = 2_000L
        private const val LAST_KNOWN_MS = 10 * 60 * 1000L
        private const val PROFILE_PART_BYTES = 180
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
        private const val TYPE_PEER_LEAVE: Byte = 9
        private const val TYPE_PEER_HERE: Byte = 10
        private const val KIND_RECEIVED: Byte = 1
        private const val KIND_READ: Byte = 2
        private const val FLAG_PUBLIC = 0
        private const val FLAG_PRIVATE = 1
        private const val FLAG_LIVE = 2
        const val PREF_BLE = "radio_ble"
        const val PREF_LONG_RANGE = "radio_long_range"
        /** Garder le scan allumé pendant les envois (activé par défaut). */
        const val PREF_SCAN_SEND = "scan_during_send"
        const val COMPANY_ID = 0xFFFE
        @Volatile var instance: BleMeshManager? = null
        @Volatile var listener: ((String) -> Unit)? = null
        @Volatile var blobListener: ((ByteArray, String?) -> Unit)? = null
        @Volatile var liveListener: ((String, ByteArray) -> Unit)? = null
        @Volatile var peersListener: ((List<MeshPeer>) -> Unit)? = null
        @Volatile var wifiSender: ((Int, ByteArray, String?) -> Int)? = null
    }
}
