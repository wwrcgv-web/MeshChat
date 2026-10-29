package com.example.meshchat.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
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
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey

/** Un téléphone trouvé, identifié par son identifiant MeshChat (16 caractères hexadécimaux). */
data class MeshPeer(val id: String)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

@SuppressLint("MissingPermission")
class BleMeshManager(private val context: Context) {

    /** Suivi d'un envoi privé vers plusieurs pairs : « Parti » si au moins un a reçu, sinon « Non envoyé ». */
    private class SendGroup(val msgId: String, val total: Int) {
        @Volatile var done = 0
        @Volatile var ok = 0
    }

    /** Une connexion à un pair, avec les trames à lui envoyer. */
    private class BleJob(val device: BluetoothDevice, val frames: List<ByteArray>, val group: SendGroup?)

    /** Résultat d'une tentative de connexion : réussie ou non, avec la raison. */
    private class Attempt(val ok: Boolean, val reason: String)

    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val serviceUuid = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid = UUID.fromString("0000feee-0000-1000-8000-00805f9b34fb")
    private var server: BluetoothGattServer? = null
    private var advertising = false
    @Volatile private var scanning = false
    @Volatile private var stopped = false

    /** Une seule connexion Bluetooth à la fois : les envois font la queue ici. */
    private val bleExecutor = Executors.newSingleThreadExecutor()
    /** Connexions en attente ou en cours. Le scan reprend quand ce compteur revient à zéro. */
    private val pendingJobs = AtomicInteger(0)

    /** Préférences du téléphone : identifiant, clés, et clés des pairs rencontrés. */
    private val prefs = context.getSharedPreferences("meshchat", Context.MODE_PRIVATE)

    private val peers = ConcurrentHashMap<String, BluetoothDevice>()   // adresse Bluetooth -> appareil
    private val peerIds = ConcurrentHashMap<String, String>()          // adresse Bluetooth -> identifiant MeshChat
    private val peerKeys = ConcurrentHashMap<String, PublicKey>()      // identifiant MeshChat -> clé publique (gardée entre les lancements)
    private val seenIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val seenSecret: MutableSet<String> = ConcurrentHashMap.newKeySet()    // messages privés et accusés déjà vus
    private val seenFragments: MutableSet<String> = ConcurrentHashMap.newKeySet() // fragments déjà vus
    private val assemblies = ConcurrentHashMap<String, MeshChunker.Assembly>()
    private val pendingRead = ConcurrentHashMap<String, MutableList<String>>()    // expéditeur -> messages reçus non lus
    private val queue = MeshQueue()
    private val store = MeshStore()
    private val main = Handler(Looper.getMainLooper())

    /** Identifiant du dernier message privé envoyé (pour suivre ses accusés). Null pour un message public. */
    @Volatile var lastSentId: String? = null

    /** Identifiant fixe de ce téléphone, gardé entre les lancements. */
    val myId: ByteArray = loadOrCreateId()

    /** Paire de clés de ce téléphone, gardée entre les lancements (la clé privée ne quitte jamais le téléphone). */
    private val keyPair: KeyPair = loadOrCreateKeyPair()

    init {
        instance = this
        loadPeerKeys()
    }

    /** Lit l'identifiant enregistré sur le téléphone, ou en crée un la première fois. */
    private fun loadOrCreateId(): ByteArray {
        val saved = prefs.getString("my_id", null)
        if (saved != null && saved.length == MeshPacket.ID_SIZE * 2) {
            return saved.hexToBytes()
        }
        val fresh = MeshPacket.newPeerId()
        prefs.edit().putString("my_id", fresh.toHex()).apply()
        return fresh
    }

    /** Relit la paire de clés enregistrée, ou en crée une la première fois. */
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

    /** Recharge les clés des téléphones déjà rencontrés. */
    private fun loadPeerKeys() {
        try {
            val factory = KeyFactory.getInstance("EC")
            prefs.all.forEach { (name, value) ->
                if (name.startsWith("peer_") && value is String) {
                    try {
                        peerKeys[name.removePrefix("peer_")] = factory.generatePublic(X509EncodedKeySpec(value.hexToBytes()))
                    } catch (e: Exception) {
                        Log.w(TAG, "Clé de pair illisible ignorée : $name")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Impossible de relire les clés des pairs", e)
        }
    }

    /** Envoie une ligne au journal (et au compteur de debug). Aussi utilisé par le Wi-Fi Aware. */
    fun emit(msg: String) {
        Log.i(TAG, msg)
        main.post { listener?.invoke(msg) }
    }

    /** Publie à l'écran la liste des téléphones identifiés. */
    private fun publishPeers() {
        val list = peerIds.values.distinct().sorted().map { MeshPeer(it) }
        main.post { peersListener?.invoke(list) }
    }

    /** Secret commun avec ce téléphone, ou null si sa clé n'a pas encore été reçue. */
    fun sharedKeyWith(peerId: String): SecretKey? {
        val theirs = peerKeys[peerId] ?: return null
        return CryptoEngine.sharedKey(keyPair.private, theirs)
    }

    /**
     * Essaie de déchiffrer avec la clé de chaque téléphone connu.
     * Retourne l'identifiant de l'expéditeur et le contenu, ou null si le message n'est pas pour nous.
     */
    private fun openForMe(cipher: ByteArray): Pair<String, ByteArray>? {
        for (entry in peerKeys.entries) {
            val key = CryptoEngine.sharedKey(keyPair.private, entry.value)
            val plain = CryptoEngine.decrypt(cipher, key)
            if (plain != null) return Pair(entry.key, plain)
        }
        return null
    }

    fun startAdvertising() {
        emit("Mon identifiant : ${myId.toHex().take(8)}")
        emit("Clés mémorisées : ${peerKeys.size}")
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run { emit("Annonce Bluetooth indisponible"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(serviceUuid)).setIncludeDeviceName(false).build()
        // L'identifiant voyage dans la « réponse au scan » : les autres téléphones le lisent en cherchant
        val response = AdvertiseData.Builder().addManufacturerData(COMPANY_ID, myId).setIncludeDeviceName(false).build()
        try { advertiser.startAdvertising(settings, data, response, callback) } catch (e: SecurityException) { emit("Permission Bluetooth manquante") }
        startScan()
    }

    fun startScan() {
        if (scanning) return
        if (adapter?.bluetoothLeScanner == null) { emit("Scan indisponible"); return }
        if (startScanQuietly()) emit("Recherche de pairs...")
    }

    /** Démarre le scan sans rien écrire dans le journal. Retourne true si le scan a démarré. */
    private fun startScanQuietly(): Boolean {
        if (scanning || stopped) return false
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

    /** Arrête le scan pendant un envoi : Android ne sait pas bien ouvrir une connexion en même temps. */
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
     * Envoie un message texte.
     * Sans [toPeerId] : message public (200 octets maximum), relayé par les téléphones intermédiaires.
     * Avec [toPeerId] : message privé chiffré (180 octets maximum), relayé, illisible pour les intermédiaires.
     * Retourne le nombre de pairs visés, ou 1 si le message est gardé en attente.
     * Le résultat réel arrive ensuite dans le journal : « Parti », « Non envoyé » ou « En attente », avec l'identifiant.
     */
    fun sendMessage(text: String, toPeerId: String? = null): Int {
        lastSentId = null
        val bytes = text.toByteArray(Charsets.UTF_8)

        if (toPeerId == null) {
            if (bytes.size > MAX_TEXT_BYTES) {
                emit("Message trop long (200 caractères maximum)")
                return 0
            }
            val packet = MeshPacket(UUID.randomUUID(), myId, ByteArray(MeshPacket.ID_SIZE), MeshPacket.DEFAULT_TTL, text)
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

        // Message privé : chiffré avec le secret commun avec le destinataire
        if (bytes.size > MAX_PRIVATE_BYTES) {
            emit("Message privé trop long (180 caractères maximum)")
            return 0
        }
        val key = sharedKeyWith(toPeerId) ?: run {
            emit("Clé de ce pair pas encore reçue : message non envoyé")
            return 0
        }
        val targets = peers.values.toList()
        val msgId = ByteArray(MeshPacket.ID_SIZE).also { SecureRandom().nextBytes(it) }
        seenSecret.add(msgId.toHex())
        lastSentId = msgId.toHex()
        val tag = MeshStore.tagFor(toPeerId)
        val cipher = CryptoEngine.encrypt(bytes, key)
        store.add(msgId, tag, cipher)
        if (targets.isEmpty()) {
            emit("En attente ${msgId.toHex()}")
            emit("Aucun pair : message privé gardé en attente (10 min)")
            return 1
        }
        // Trame : type (1) + TTL (1) + identifiant (8) + étiquette (8) + données chiffrées
        val frame = byteArrayOf(TYPE_SECRET, MeshPacket.DEFAULT_TTL.toByte()) + msgId + tag + cipher
        val group = SendGroup(msgId.toHex(), targets.size)
        targets.forEach { enqueue(it, listOf(frame), group) }
        return targets.size
    }

    /**
     * Envoie une photo, un vocal, un fichier ou une vidéo.
     * D'abord par Wi-Fi Aware (voisins directs, sans limite de taille).
     * Sinon par Bluetooth, en fragments, pour les petits contenus seulement (250 Ko maximum).
     * Sans [toPeerId] : envoi public. Avec [toPeerId] : contenu chiffré de bout en bout.
     */
    fun sendBlob(bytes: ByteArray, toPeerId: String? = null): Int {
        val payload = if (toPeerId == null) {
            bytes
        } else {
            val key = sharedKeyWith(toPeerId) ?: run {
                emit("Clé de ce pair pas encore reçue : fichier non envoyé")
                return 0
            }
            CryptoEngine.encrypt(bytes, key)
        }
        val flag = if (toPeerId == null) FLAG_PUBLIC else FLAG_PRIVATE

        // Wi-Fi Aware en premier
        val viaWifi = wifiSender?.invoke(flag, payload, toPeerId) ?: 0
        if (viaWifi > 0) return viaWifi

        // Repli Bluetooth : seulement pour les petits contenus
        if (payload.size > MAX_BLE_BLOB) {
            emit("Fichier refusé : ${bytes.size / 1024} Ko, limite Bluetooth ${MAX_BLE_BLOB / 1000} Ko")
            return 0
        }
        val targets = peers.values.toList()
        if (targets.isEmpty()) { emit("Aucun pair : fichier non envoyé"); return 0 }
        val ttl = if (toPeerId == null) 1 else MeshPacket.DEFAULT_TTL
        val fragments = MeshChunker.split(MeshChunker.newContentId(), payload)
        // Trame : type (1) + TTL (1) + drapeau public/privé (1) + fragment (identifiant du contenu, numéro, total, données)
        val frames = fragments.map { byteArrayOf(TYPE_FRAGMENT, ttl.toByte(), flag.toByte()) + it }
        emit("Envoi du fichier : ${bytes.size} octets en ${frames.size} fragments")
        targets.forEach { enqueue(it, frames) }
        return targets.size
    }

    /** Marque comme lus les messages reçus de ce téléphone : il renvoie un accusé « Lu » pour chacun. */
    fun markRead(peerId: String) {
        val list = pendingRead.remove(peerId) ?: return
        list.forEach { sendAck(peerId, it.hexToBytes(), KIND_READ) }
    }

    /** Contenu reçu par Wi-Fi Aware : même traitement que par Bluetooth. Appelé par le service. */
    fun onWifiBlob(flag: Int, payload: ByteArray) {
        if (flag == FLAG_PUBLIC) {
            deliverBlob(payload, null)
            return
        }
        val opened = openForMe(payload) ?: return
        deliverBlob(opened.second, opened.first)
    }

    /** Remet un contenu complet à l'écran. [from] vaut null pour la conversation générale. */
    private fun deliverBlob(bytes: ByteArray, from: String?) {
        emit("Fichier reçu : ${bytes.size} octets")
        val receive = blobListener
        if (receive != null) main.post { receive(bytes, from) }
    }

    private fun textFrame(packet: MeshPacket): ByteArray = byteArrayOf(TYPE_TEXT) + packet.encode()

    /** Trame d'échange de clé : type (1) + mon identifiant (8) + ma clé publique. */
    private fun keyFrame(): ByteArray = byteArrayOf(TYPE_KEY) + myId + CryptoEngine.encodePublic(keyPair.public)

    /** Envoie une trame à un pair, via la file d'attente. */
    private fun sendTo(device: BluetoothDevice, frame: ByteArray) = enqueue(device, listOf(frame))

    /**
     * Met une connexion dans la file d'attente. Une seule connexion est ouverte à la fois.
     * Le scan est arrêté dès qu'un envoi est en attente, et repris quand la file est vide.
     */
    private fun enqueue(device: BluetoothDevice, frames: List<ByteArray>, group: SendGroup? = null) {
        if (frames.isEmpty() || stopped) return
        pendingJobs.incrementAndGet()
        pauseScan()
        try {
            bleExecutor.execute { runJob(BleJob(device, frames, group)) }
        } catch (e: RejectedExecutionException) {
            finishJob()
        }
    }

    /** Exécute une connexion de la file. Ne jamais l'appeler directement : passer par enqueue. */
    private fun runJob(job: BleJob) {
        try {
            val ok = try {
                sendWithRetry(job.device, job.frames)
            } catch (e: Exception) {
                Log.w(TAG, "Envoi interrompu", e)
                false
            }
            job.group?.let { g ->
                g.done++
                if (ok) g.ok++
                if (g.done == g.total) {
                    if (g.ok > 0) emit("Parti ${g.msgId}") else emit("Non envoyé ${g.msgId}")
                }
            }
        } finally {
            finishJob()
        }
    }

    /** Une connexion terminée : si plus rien n'attend, le scan reprend. */
    private fun finishJob() {
        if (pendingJobs.decrementAndGet() == 0) startScanQuietly()
    }

    /** Essaie jusqu'à trois fois, avec une pause qui s'allonge entre les essais. */
    private fun sendWithRetry(device: BluetoothDevice, frames: List<ByteArray>): Boolean {
        for (attempt in 1..MAX_ATTEMPTS) {
            val result = connectAndWrite(device, frames)
            if (result.ok) {
                emit("Envoyé à ${device.address}")
                return true
            }
            emit("Échec envoi à ${device.address} (${result.reason}), essai $attempt/$MAX_ATTEMPTS")
            if (attempt < MAX_ATTEMPTS) Thread.sleep(RETRY_PAUSE_MS * attempt)
        }
        return false
    }

    /** Une connexion complète : ouvre le lien, écrit toutes les trames, puis ferme. Retourne le résultat. */
    private fun connectAndWrite(device: BluetoothDevice, frames: List<ByteArray>): Attempt {
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

        val handle: BluetoothGatt? = try {
            device.connectGatt(context, false, connectCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            return Attempt(false, "permission refusée")
        }

        val timeoutMs = TIMEOUT_BASE_MS + frames.size * TIMEOUT_PER_FRAME_MS
        if (!finished.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            finish(Attempt(false, "délai dépassé"))
            handle?.disconnect()
            handle?.close()
        }
        return outcome.get() ?: Attempt(false, "inconnu")
    }

    /** Envoie une trame à un pair, comme message isolé. */
    private fun sendFramesTo(device: BluetoothDevice, frames: List<ByteArray>) = enqueue(device, frames)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val r = result ?: return
            val mac = r.device.address
            peers.put(mac, r.device)
            val idBytes = r.scanRecord?.getManufacturerSpecificData(COMPANY_ID)
            if (idBytes != null && idBytes.size == MeshPacket.ID_SIZE) {
                val id = idBytes.toHex()
                if (peerIds.put(mac, id) == null) {
                    // Un même téléphone peut changer d'adresse Bluetooth : on retire les anciennes adresses
                    peerIds.filter { it.value == id && it.key != mac }.keys.toList().forEach {
                        peers.remove(it)
                        peerIds.remove(it)
                    }
                    emit("Pair trouvé : ${id.take(8)}")
                    publishPeers()
                    // Premier contact : une seule connexion pour la clé, les messages gardés et les messages publics en attente
                    val frames = listOf(keyFrame()) + storedFramesFor(id) + queuedFrames()
                    enqueue(r.device, frames)
                }
            }
        }
        override fun onScanFailed(errorCode: Int) { emit("Échec scan : $errorCode") }
    }

    /** Trames des messages publics qui attendaient un pair. Vide s'il n'y en a pas. */
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

    /** Premier octet = type de la trame. */
    private fun handleIncoming(fromAddress: String, value: ByteArray) {
        if (value.isEmpty()) return
        when (value[0]) {
            TYPE_TEXT -> handleText(fromAddress, value.copyOfRange(1, value.size))
            TYPE_FRAGMENT -> {
                if (value.size < 3) { emit("Fragment mal formé"); return }
                handleFragment(fromAddress, value[1].toInt() and 0xFF, value[2].toInt() and 0xFF, value.copyOfRange(3, value.size))
            }
            TYPE_KEY -> handleKey(value.copyOfRange(1, value.size))
            TYPE_SECRET -> handleSecret(fromAddress, value.copyOfRange(1, value.size))
            TYPE_ACK -> handleAck(fromAddress, value.copyOfRange(1, value.size))
            else -> emit("Message reçu inconnu")
        }
    }

    /** Reçoit la clé publique d'un téléphone, la garde en mémoire et dans les préférences. */
    private fun handleKey(body: ByteArray) {
        if (body.size <= MeshPacket.ID_SIZE) { emit("Clé mal formée"); return }
        val senderId = body.copyOfRange(0, MeshPacket.ID_SIZE).toHex()
        val publicBytes = body.copyOfRange(MeshPacket.ID_SIZE, body.size)
        try {
            val key = CryptoEngine.decodePublic(publicBytes)
            peerKeys[senderId] = key
            // Sauvegarde : la clé reste disponible après un redémarrage de l'application
            prefs.edit().putString("peer_$senderId", publicBytes.toHex()).apply()
            emit("Clé reçue de ${senderId.take(8)}")
        } catch (e: Exception) {
            emit("Clé invalide reçue de ${senderId.take(8)}")
        }
    }

    /**
     * Message privé chiffré. Le corps contient : TTL (1) + identifiant aléatoire (8) + étiquette (8) + données chiffrées.
     * Si on peut le déchiffrer, il est pour nous : on affiche, on envoie l'accusé « Reçu ».
     * Sinon, on le garde dans la réserve pour le livrer plus tard. Dans tous les cas, on le relaie.
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

    /** Transmet un message privé chiffré à tous les pairs, sauf celui qui vient de nous l'envoyer. */
    private fun relaySecret(ttl: Int, msgId: ByteArray, tag: ByteArray, cipher: ByteArray, exceptAddress: String) {
        val targets = peers.values.filter { it.address != exceptAddress }
        if (targets.isEmpty()) return
        emit("Relais privé vers ${targets.size} pair(s) (TTL $ttl)")
        val frame = byteArrayOf(TYPE_SECRET, ttl.toByte()) + msgId + tag + cipher
        targets.forEach { sendTo(it, frame) }
    }

    /** Trames gardées pour ce téléphone : à envoyer dès qu'il apparaît. */
    private fun storedFramesFor(peerId: String): List<ByteArray> {
        val items = store.forTag(MeshStore.tagFor(peerId))
        if (items.isNotEmpty()) emit("Livraison de ${items.size} message(s) gardé(s) à ${peerId.take(8)}")
        return items.map { byteArrayOf(TYPE_SECRET, 1.toByte()) + it.id + it.tag + it.cipher }
    }

    /**
     * Envoie un accusé chiffré à l'expéditeur (Reçu ou Lu).
     * L'accusé contient : le genre (1 octet) + l'identifiant du message concerné (8), chiffrés avec la clé de l'expéditeur.
     */
    private fun sendAck(peerId: String, originalId: ByteArray, kind: Byte) {
        val key = sharedKeyWith(peerId) ?: return
        val ackId = ByteArray(MeshPacket.ID_SIZE).also { SecureRandom().nextBytes(it) }
        seenSecret.add(ackId.toHex())
        val cipher = CryptoEngine.encrypt(byteArrayOf(kind) + originalId, key)
        val frame = byteArrayOf(TYPE_ACK, MeshPacket.DEFAULT_TTL.toByte()) + ackId + cipher
        peers.values.toList().forEach { sendTo(it, frame) }
    }

    /** Reçoit un accusé. Si c'est pour un de nos messages, on affiche « Statut ... ». Sinon on le relaie. */
    private fun handleAck(fromAddress: String, body: ByteArray) {
        if (body.size < 1 + MeshPacket.ID_SIZE + 28) return
        val ttl = body[0].toInt() and 0xFF
        val ackId = body.copyOfRange(1, 1 + MeshPacket.ID_SIZE)
        val cipher = body.copyOfRange(1 + MeshPacket.ID_SIZE, body.size)
        if (!seenSecret.add(ackId.toHex())) return

        val opened = openAck(cipher)
        if (opened != null) {
            val label = if (opened.first == KIND_READ) "lu" else "reçu"
            emit("Statut ${opened.second} $label")
        }

        if (ttl > 1) {
            val targets = peers.values.filter { it.address != fromAddress }
            val frame = byteArrayOf(TYPE_ACK, (ttl - 1).toByte()) + ackId + cipher
            targets.forEach { sendTo(it, frame) }
        }
    }

    /** Essaie de lire un accusé avec la clé de chaque téléphone connu. Retourne le genre et l'identifiant du message. */
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

    /**
     * Message public reçu : affiché pour tout le monde. Les messages publics sont relayés.
     * Format de la ligne : « Reçu de <expéditeur> : texte ».
     */
    private fun handleText(fromAddress: String, body: ByteArray) {
        val packet = MeshPacket.decode(body)
        if (packet == null) { emit("Message reçu mal formé"); return }
        if (!seenIds.add(packet.id)) return
        val isPublic = packet.recipient.all { it == 0.toByte() }
        if (isPublic) emit("Reçu de ${packet.sender.toHex()} : ${packet.text}")
        if (packet.canRelay()) relay(packet.withDecrementedTtl(), exceptAddress = fromAddress)
    }

    /**
     * Reçoit un fragment. Un fragment privé est relayé sans être ouvert.
     * Quand un contenu est complet : public = affiché ; privé = déchiffré seulement si c'est pour nous.
     */
    private fun handleFragment(fromAddress: String, ttl: Int, flag: Int, chunk: ByteArray) {
        assemblies.entries.removeIf { it.value.isExpired() }
        val (key, total, part) = MeshChunker.parse(chunk) ?: run { emit("Fragment mal formé"); return }

        // Chaque fragment n'est traité qu'une fois, même reçu par plusieurs chemins
        if (!seenFragments.add("$key:${part.first}")) return
        if (seenFragments.size > MAX_SEEN_FRAGMENTS) seenFragments.clear()

        if (flag == FLAG_PRIVATE && ttl > 1) relayFragment(ttl - 1, chunk, fromAddress)

        val assembly = assemblies.getOrPut(key) { MeshChunker.Assembly(total) }
        assembly.add(part.first, part.second)
        if (!assembly.isComplete()) return
        assemblies.remove(key)
        val bytes = assembly.assemble()

        if (flag == FLAG_PUBLIC) {
            deliverBlob(bytes, null)
            return
        }

        // Fichier privé : on n'affiche que s'il est pour nous (déchiffrement réussi)
        val opened = openForMe(bytes) ?: return
        deliverBlob(opened.second, opened.first)
    }

    /** Transmet un fragment privé à tous les pairs, sauf celui qui vient de nous l'envoyer. */
    private fun relayFragment(ttl: Int, chunk: ByteArray, exceptAddress: String) {
        val targets = peers.values.filter { it.address != exceptAddress }
        if (targets.isEmpty()) return
        emit("Relais fichier privé vers ${targets.size} pair(s) (TTL $ttl)")
        val frame = byteArrayOf(TYPE_FRAGMENT, ttl.toByte(), FLAG_PRIVATE.toByte()) + chunk
        targets.forEach { sendTo(it, frame) }
    }

    /** Transmet un texte public à tous les pairs, sauf celui qui vient de nous l'envoyer. */
    private fun relay(packet: MeshPacket, exceptAddress: String) {
        val targets = peers.values.filter { it.address != exceptAddress }
        if (targets.isEmpty()) return
        emit("Relais vers ${targets.size} pair(s) (TTL ${packet.ttl})")
        val frame = textFrame(packet)
        targets.forEach { sendTo(it, frame) }
    }

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true; emit("Annonce Bluetooth active") }
        override fun onStartFailure(errorCode: Int) { advertising = false; emit("Échec annonce : $errorCode") }
    }

    fun stop() {
        stopped = true
        bleExecutor.shutdownNow()
        try { if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (e: SecurityException) { Log.w(TAG, "Arrêt scan impossible", e) }
        try { if (advertising) adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) } catch (e: SecurityException) { Log.w(TAG, "Arrêt annonce impossible", e) }
        try { server?.close() } catch (e: SecurityException) { Log.w(TAG, "Fermeture serveur impossible", e) }
        scanning = false; advertising = false; server = null
        // Les clés des pairs ne sont PAS effacées : elles restent sauvegardées
        peers.clear()
        peerIds.clear()
        assemblies.clear()
        seenFragments.clear()
        pendingRead.clear()
        publishPeers()
        instance = null
    }

    companion object {
        private const val TAG = "MeshChatBLE"
        private const val MAX_TEXT_BYTES = 200
        private const val MAX_PRIVATE_BYTES = 180
        private const val MAX_SEEN_FRAGMENTS = 5000
        /** Taille maximale d'un contenu envoyé par Bluetooth. Au-delà, il faut Wi-Fi Aware. */
        private const val MAX_BLE_BLOB = 250_000
        /** Nombre d'essais par connexion, et pause de base entre deux essais. */
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_PAUSE_MS = 1_500L
        /** Délai maximal d'une connexion : une base, plus un peu par trame à envoyer. */
        private const val TIMEOUT_BASE_MS = 20_000L
        private const val TIMEOUT_PER_FRAME_MS = 500L
        private const val TYPE_TEXT: Byte = 1
        private const val TYPE_FRAGMENT: Byte = 2
        private const val TYPE_KEY: Byte = 3
        private const val TYPE_SECRET: Byte = 4
        private const val TYPE_ACK: Byte = 5
        private const val KIND_RECEIVED: Byte = 1
        private const val KIND_READ: Byte = 2
        private const val FLAG_PUBLIC = 0
        private const val FLAG_PRIVATE = 1
        /** Code fabricant utilisé pour porter l'identifiant MeshChat dans la réponse au scan. */
        const val COMPANY_ID = 0xFFFE
        @Volatile var instance: BleMeshManager? = null
        /** L'écran branche ici une fonction pour recevoir les messages du journal. */
        @Volatile var listener: ((String) -> Unit)? = null
        /**
         * Reçoit un contenu complet. Le second paramètre vaut null pour la conversation générale,
         * ou l'identifiant de l'expéditeur pour une discussion privée.
         */
        @Volatile var blobListener: ((ByteArray, String?) -> Unit)? = null
        /** L'écran branche ici une fonction pour recevoir la liste des téléphones identifiés. */
        @Volatile var peersListener: ((List<MeshPeer>) -> Unit)? = null
        /**
         * Envoi par Wi-Fi Aware, branché par le service.
         * Reçoit (drapeau, contenu, destinataire) et retourne le nombre de voisins joignables (0 = repli Bluetooth).
         */
        @Volatile var wifiSender: ((Int, ByteArray, String?) -> Int)? = null
    }
}
