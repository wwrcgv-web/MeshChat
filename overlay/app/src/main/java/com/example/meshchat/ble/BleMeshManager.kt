package com.example.meshchat.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleMeshManager(private val context: Context) {
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val serviceUuid = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")
    private val characteristicUuid = UUID.fromString("0000feee-0000-1000-8000-00805f9b34fb")
    private var server: BluetoothGattServer? = null
    private var advertising = false
    private var scanning = false
    private val peers = ConcurrentHashMap<String, BluetoothDevice>()
    private val seenIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private val main = Handler(Looper.getMainLooper())

    /** Identifiant fixe de ce téléphone, envoyé dans chaque message. */
    val myId: ByteArray = MeshPacket.newPeerId()

    init { instance = this }

    private fun emit(msg: String) {
        Log.i(TAG, msg)
        main.post { listener?.invoke(msg) }
    }

    private fun shortId(bytes: ByteArray): String =
        bytes.take(4).joinToString("") { "%02x".format(it) }

    fun startAdvertising() {
        emit("Mon identifiant : ${shortId(myId)}")
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run { emit("Annonce Bluetooth indisponible"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(serviceUuid)).setIncludeDeviceName(false).build()
        try { advertiser.startAdvertising(settings, data, callback) } catch (e: SecurityException) { emit("Permission Bluetooth manquante") }
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

    /** Envoie un message texte (200 octets maximum) à tous les pairs trouvés. Retourne le nombre de pairs visés. */
    fun sendMessage(text: String): Int {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            emit("Message trop long (200 caractères maximum)")
            return 0
        }
        val packet = MeshPacket.newMessage(myId, text)
        seenIds.add(packet.id)
        val data = packet.encode()
        val targets = peers.values.toList()
        if (targets.isEmpty()) { emit("Aucun pair trouvé : message non envoyé"); return 0 }
        targets.forEach { sendTo(it, data) }
        return targets.size
    }

    private fun sendTo(device: BluetoothDevice, data: ByteArray) {
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
                if (ch == null) { emit("Service MeshChat introuvable chez ${device.address}"); gatt?.disconnect(); return }
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = data
                gatt.writeCharacteristic(ch)
            }
            override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                emit(if (status == BluetoothGatt.GATT_SUCCESS) "Envoyé à ${device.address}" else "Échec envoi (code $status)")
                gatt?.disconnect()
            }
        }
        try { device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE) } catch (e: SecurityException) { emit("Permission connexion manquante") }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val dev = result?.device ?: return
            if (peers.put(dev.address, dev) == null) emit("Pair trouvé : ${dev.address} (${peers.size} au total)")
        }
        override fun onScanFailed(errorCode: Int) { emit("Échec scan : $errorCode") }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
            if (characteristic?.uuid == characteristicUuid && value != null) handleIncoming(device?.address ?: "", value)
            if (responseNeeded && device != null) {
                try { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) } catch (e: SecurityException) { Log.e(TAG, "Réponse GATT impossible", e) }
            }
        }
    }

    /**
     * Décode un paquet reçu, ignore les doublons, affiche les messages qui nous sont destinés,
     * puis relaie le message aux autres pairs si son TTL le permet.
     */
    private fun handleIncoming(fromAddress: String, data: ByteArray) {
        val packet = MeshPacket.decode(data)
        if (packet == null) { emit("Message reçu mal formé"); return }
        if (!seenIds.add(packet.id)) return
        val forMe = packet.recipient.all { it == 0.toByte() } || packet.recipient.contentEquals(myId)
        if (forMe) emit("Reçu de ${shortId(packet.sender)} : ${packet.text}")
        if (packet.canRelay()) relay(packet.withDecrementedTtl(), exceptAddress = fromAddress)
    }

    /** Transmet un paquet à tous les pairs, sauf celui qui vient de nous l'envoyer. */
    private fun relay(packet: MeshPacket, exceptAddress: String) {
        val targets = peers.values.filter { it.address != exceptAddress }
        if (targets.isEmpty()) return
        val data = packet.encode()
        emit("Relais vers ${targets.size} pair(s) (TTL ${packet.ttl})")
        targets.forEach { sendTo(it, data) }
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
        instance = null
    }

    companion object {
        private const val TAG = "MeshChatBLE"
        private const val MAX_TEXT_BYTES = 200
        @Volatile var instance: BleMeshManager? = null
        /** L'écran branche ici une fonction pour recevoir les messages du journal. */
        @Volatile var listener: ((String) -> Unit)? = null
    }
}
