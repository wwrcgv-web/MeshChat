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
import java.security.KeyPair
import java.security.PublicKey
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/** Un téléphone trouvé, identifié par son identifiant MeshChat (16 caractères hexadécimaux). */
data class MeshPeer(val id: String)

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

@SuppressLint("MissingPermission")
class BleMeshManager(private val context: Context) {
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val serviceUuid = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid = UUID.fromString("0000feee-0000-1000-8000-00805f9b34fb")
    private var server: BluetoothGattServer? = null
    private var advertising = false
    private var scanning = false
    private val peers = ConcurrentHashMap<String, BluetoothDevice>()   // adresse Bluetooth -> appareil
    private val peerIds = ConcurrentHashMap<String, String>()          // adresse Bluetooth -> identifiant MeshChat
    private val peerKeys = ConcurrentHashMap<String, PublicKey>()      // identifiant MeshChat -> clé publique reçue
    private val seenIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val seenSecret: MutableSet<String> = ConcurrentHashMap.newKeySet()    // messages privés déjà vus
    private val seenFragments: MutableSet<String> = ConcurrentHashMap.newKeySet() // fragments déjà vus
    private val assemblies = ConcurrentHashMap<String, MeshChunker.Assembly>()
    private val queue = MeshQueue()
    private val main = Handler(Looper.getMainLooper())

    /** Identifiant fixe de ce téléphone, gardé entre les lancements. */
    val myId: ByteArray = loadOrCreateId()

    /** Paire de clés de ce téléphone (la clé privée ne quitte jamais le téléphone). */
    private val keyPair: KeyPair = CryptoEngine.newKeyPair()

    init { instance = this }

    /** Lit l'identifiant enregistré sur le téléphone, ou en crée un la première fois. */
    private fun loadOrCreateId(): ByteArray {
        val prefs = context.getSharedPreferences("meshchat", Context.MODE_PRIVATE)
        val saved = prefs.getString("my_id", null)
        if (saved != null && saved.length == MeshPacket.ID_SIZE * 2) {
            return saved.hexToBytes()
        }
        val fresh = MeshPacket.newPeerId()
        prefs.edit().putString("my_id", fresh.toHex()).apply()
        return fresh
    }

    private fun emit(msg: String) {
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

    fun startAdvertising() {
        emit("Mon identifiant : ${myId.toHex().take(8)}")
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
        val scanner = adapter?.bluetoothLeScanner ?: run { emit("Scan indisponible"); return }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            emit("Recherche de pairs...")
        } catch (e: SecurityException) { emit("Permission scan manquante") }
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
     * Avec [toPeerId] : message privé chiffré (180 octets maximum), relayé, lisible seulement par le destinataire.
     * Retourne le nombre de pairs visés, ou 1 si le message public est mis en attente.
     */
    fun sendMessage(text: String, toPeerId: String? = null): Int {
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
        if (targets.isEmpty()) {
            emit("Aucun pair : message privé non envoyé")
            return 0
        }
        val msgId = ByteArray(MeshPacket.ID_SIZE).also { SecureRandom().nextBytes(it) }
        seenSecret.add(msgId.toHex())
        // Trame : type (1) + TTL (1) + identifiant du message (8) + expéditeur (8) + destinataire (8) + données chiffrées
        val frame = byteArrayOf(TYPE_SECRET, MeshPacket.DEFAULT_TTL.toByte()) +
            msgId + myId + toPeerId.hexToBytes() + CryptoEngine.encrypt(bytes, key)
        targets.forEach { sendTo(it, frame) }
        return targets.size
    }

    /**
     * Envoie une photo ou un vocal, découpé en fragments.
     * Sans [toPeerId] : envoi public, voisins directs. Avec [toPeerId] : contenu chiffré, relayé jusqu'au destinataire.
     */
    fun sendBlob(bytes: ByteArray, toPeerId: String? = null): Int {
        val targets = peers.values.toList()
        if (targets.isEmpty()) { emit("Aucun pair : fichier non envoyé"); return 0 }

        val payload = if (toPeerId == null) {
            bytes
        } else {
            val key = sharedKeyWith(toPeerId) ?: run {
                emit("Clé de ce pair pas encore reçue : fichier non envoyé")
                return 0
            }
            CryptoEngine.encrypt(bytes, key)
        }

        val recipient = toPeerId?.hexToBytes() ?: ByteArray(MeshPacket.ID_SIZE)
        val fragments = MeshChunker.split(MeshChunker.newContentId(), payload)
        // Trame : type (1) + TTL (1) + expéditeur (8) + destinataire (8) + fragment
        val frames = fragments.map { byteArrayOf(TYPE_FRAGMENT, MeshPacket.DEFAULT_TTL.toByte()) + myId + recipient + it }
        emit("Envoi du fichier : ${bytes.size} octets en ${frames.size} fragments")
        targets.forEach { sendFramesTo(it, frames) }
        return targets.size
    }

    private fun textFrame(packet: MeshPacket): ByteArray = byteArrayOf(TYPE_TEXT) + packet.encode()

    /** Trame d'échange de clé : type (1) + mon identifiant (8) + ma clé publique. */
    private fun keyFrame(): ByteArray = byteArrayOf(TYPE_KEY) + myId + CryptoEngine.encodePublic(keyPair.public)

    private fun sendTo(device: BluetoothDevice, frame: ByteArray) = sendFramesTo(device, listOf(frame))

    /** Ouvre une connexion et envoie les trames une par une, en attendant la confirmation de chaque envoi. */
    private fun sendFramesTo(device: BluetoothDevice, frames: List<ByteArray>) {
        if (frames.isEmpty()) return
        var index = 0
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt?.requestMtu(247)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (status != BluetoothGatt.GATT_SUCCESS) emit("Échec connexion à ${device.address} (code $status)")
                    gatt?.close()
                }
            }
            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                gatt?.discoverServices()
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                val ch = gatt?.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
                if (gatt == null || ch == null) {
                    emit("Service MeshChat introuvable chez ${device.address}")
                    gatt?.disconnect()
                    return
                }
                writeFrame(gatt, ch, frames[index])
            }
            override fun onCharacteristicWrite(gatt: BluetoothGatt?, ch: BluetoothGattCharacteristic?, status: Int) {
                if (gatt == null) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emit("Échec envoi (code $status)")
                    gatt.disconnect()
                    return
                }
                index++
                if (index < frames.size) {
                    ch?.let { writeFrame(gatt, it, frames[index]) }
                } else {
                    emit("Envoyé à ${device.address}")
                    gatt.disconnect()
                }
            }
        }
        try { device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE) } catch (e: SecurityException) { emit("Permission connexion manquante") }
    }

    private fun writeFrame(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, frame: ByteArray) {
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ch.value = frame
        gatt.writeCharacteristic(ch)
    }

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
                    // Premier échange : on envoie notre clé publique au nouveau téléphone
                    sendTo(r.device, keyFrame())
                    sendQueuedTo(r.device)
                }
            }
        }
        override fun onScanFailed(errorCode: Int) { emit("Échec scan : $errorCode") }
    }

    /** Envoie au nouveau pair les messages publics qui attendaient. */
    private fun sendQueuedTo(device: BluetoothDevice) {
        val pending = queue.drain()
        if (pending.isEmpty()) return
        emit("Envoi de ${pending.size} message(s) en attente")
        pending.forEach { sendTo(device, textFrame(it)) }
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
                if (value.size < 2) { emit("Fragment mal formé"); return }
                handleFragment(fromAddress, value[1].toInt() and 0xFF, value.copyOfRange(2, value.size))
            }
            TYPE_KEY -> handleKey(value.copyOfRange(1, value.size))
            TYPE_SECRET -> handleSecret(fromAddress, value.copyOfRange(1, value.size))
            else -> emit("Message reçu inconnu")
        }
    }

    /** Reçoit la clé publique d'un téléphone et la garde pour le chiffrement. */
    private fun handleKey(body: ByteArray) {
        if (body.size <= MeshPacket.ID_SIZE) { emit("Clé mal formée"); return }
        val senderId = body.copyOfRange(0, MeshPacket.ID_SIZE).toHex()
        val publicBytes = body.copyOfRange(MeshPacket.ID_SIZE, body.size)
        try {
            peerKeys[senderId] = CryptoEngine.decodePublic(publicBytes)
            emit("Clé reçue de ${senderId.take(8)}")
        } catch (e: Exception) {
            emit("Clé invalide reçue de ${senderId.take(8)}")
        }
    }

    /**
     * Message privé chiffré. Le corps contient : TTL (1) + identifiant (8) + expéditeur (8) + destinataire (8) + données chiffrées.
     * Si le message est pour nous, on le déchiffre. Sinon, on le relaie sans jamais pouvoir le lire.
     */
    private fun handleSecret(fromAddress: String, body: ByteArray) {
        if (body.size < 1 + MeshPacket.ID_SIZE * 3 + 28) { emit("Message privé mal formé"); return }
        val ttl = body[0].toInt() and 0xFF
        val msgId = body.copyOfRange(1, 1 + MeshPacket.ID_SIZE)
        val sender = body.copyOfRange(1 + MeshPacket.ID_SIZE, 1 + MeshPacket.ID_SIZE * 2)
        val recipient = body.copyOfRange(1 + MeshPacket.ID_SIZE * 2, 1 + MeshPacket.ID_SIZE * 3)
        val cipher = body.copyOfRange(1 + MeshPacket.ID_SIZE * 3, body.size)

        // Même message reçu par plusieurs chemins : on ne le traite qu'une fois
        if (!seenSecret.add(msgId.toHex())) return

        if (recipient.contentEquals(myId)) {
            val key = sharedKeyWith(sender.toHex()) ?: run {
                emit("Message privé reçu sans clé : ignoré")
                return
            }
            val plain = CryptoEngine.decrypt(cipher, key) ?: run {
                emit("Message privé illisible (message altéré)")
                return
            }
            emit("Reçu privé de ${sender.toHex()} : ${String(plain, Charsets.UTF_8)}")
            return
        }

        // Pas pour nous : on relaie le message chiffré, sans l'ouvrir
        if (ttl > 1) relaySecret(ttl - 1, msgId, sender, recipient, cipher, fromAddress)
    }

    /** Transmet un message privé chiffré à tous les pairs, sauf celui qui vient de nous l'envoyer. */
    private fun relaySecret(ttl: Int, msgId: ByteArray, sender: ByteArray, recipient: ByteArray, cipher: ByteArray, exceptAddress: String) {
        val targets = peers.values.filter { it.address != exceptAddress }
        if (targets.isEmpty()) return
        emit("Relais privé vers ${targets.size} pair(s) (TTL $ttl)")
        val frame = byteArrayOf(TYPE_SECRET, ttl.toByte()) + msgId + sender + recipient + cipher
        targets.forEach { sendTo(it, frame) }
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
     * Reçoit un fragment. Le corps contient : expéditeur (8) + destinataire (8) + fragment.
     * Un fragment privé pour quelqu'un d'autre est relayé sans être ouvert.
     * Un contenu privé pour nous est déchiffré une fois complet.
     */
    private fun handleFragment(fromAddress: String, ttl: Int, body: ByteArray) {
        assemblies.entries.removeIf { it.value.isExpired() }
        if (body.size < MeshPacket.ID_SIZE * 2) { emit("Fragment mal formé"); return }
        val sender = body.copyOfRange(0, MeshPacket.ID_SIZE)
        val recipient = body.copyOfRange(MeshPacket.ID_SIZE, MeshPacket.ID_SIZE * 2)
        val isPublic = recipient.all { it == 0.toByte() }
        val forMe = isPublic || recipient.contentEquals(myId)
        val fragment = body.copyOfRange(MeshPacket.ID_SIZE * 2, body.size)
        val (key, total, part) = MeshChunker.parse(fragment) ?: run { emit("Fragment mal formé"); return }

        // Chaque fragment n'est traité qu'une fois, même reçu par plusieurs chemins
        if (!seenFragments.add("$key:${part.first}")) return
        if (seenFragments.size > MAX_SEEN_FRAGMENTS) seenFragments.clear()

        if (!forMe) {
            // Fichier privé pour un autre téléphone : on le transmet sans l'ouvrir
            if (ttl > 1) relayFragment(ttl - 1, body, fromAddress)
            return
        }

        val assembly = assemblies.getOrPut(key) { MeshChunker.Assembly(total) }
        assembly.add(part.first, part.second)
        if (assembly.isComplete()) {
            assemblies.remove(key)
            var bytes = assembly.assemble()
            var from: String? = null
            if (!isPublic) {
                val secret = sharedKeyWith(sender.toHex()) ?: run {
                    emit("Fichier privé reçu sans clé : ignoré")
                    return
                }
                bytes = CryptoEngine.decrypt(bytes, secret) ?: run {
                    emit("Fichier privé illisible (fichier altéré)")
                    return
                }
                from = sender.toHex()
            }
            emit("Fichier reçu : ${bytes.size} octets")
            val receive = blobListener
            if (receive != null) main.post { receive(bytes, from) }
        }
    }

    /** Transmet un fragment privé à tous les pairs, sauf celui qui vient de nous l'envoyer. */
    private fun relayFragment(ttl: Int, body: ByteArray, exceptAddress: String) {
        val targets = peers.values.filter { it.address != exceptAddress }
        if (targets.isEmpty()) return
        emit("Relais fichier privé vers ${targets.size} pair(s) (TTL $ttl)")
        val frame = byteArrayOf(TYPE_FRAGMENT, ttl.toByte()) + body
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
        try { if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (e: SecurityException) { Log.w(TAG, "Arrêt scan impossible", e) }
        try { if (advertising) adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) } catch (e: SecurityException) { Log.w(TAG, "Arrêt annonce impossible", e) }
        try { server?.close() } catch (e: SecurityException) { Log.w(TAG, "Fermeture serveur impossible", e) }
        scanning = false; advertising = false; server = null
        peers.clear()
        peerIds.clear()
        peerKeys.clear()
        assemblies.clear()
        seenFragments.clear()
        publishPeers()
        instance = null
    }

    companion object {
        private const val TAG = "MeshChatBLE"
        private const val MAX_TEXT_BYTES = 200
        private const val MAX_PRIVATE_BYTES = 180
        private const val MAX_SEEN_FRAGMENTS = 5000
        private const val TYPE_TEXT: Byte = 1
        private const val TYPE_FRAGMENT: Byte = 2
        private const val TYPE_KEY: Byte = 3
        private const val TYPE_SECRET: Byte = 4
        /** Code fabricant utilisé pour porter l'identifiant MeshChat dans la réponse au scan. */
        const val COMPANY_ID = 0xFFFE
        @Volatile var instance: BleMeshManager? = null
        /** L'écran branche ici une fonction pour recevoir les messages du journal. */
        @Volatile var listener: ((String) -> Unit)? = null
        /**
         * L'écran branche ici une fonction pour recevoir un fichier complet.
         * Le second paramètre vaut null pour la conversation générale, ou l'identifiant de l'expéditeur pour une discussion privée.
         */
        @Volatile var blobListener: ((ByteArray, String?) -> Unit)? = null
        /** L'écran branche ici une fonction pour recevoir la liste des téléphones identifiés. */
        @Volatile var peersListener: ((List<MeshPeer>) -> Unit)? = null
    }
}
