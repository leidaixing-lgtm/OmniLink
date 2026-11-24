package com.omnilink.bridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import com.omnilink.bridge.data.OmniRepository
import com.omnilink.bridge.protocol.AncsDataReassembler
import com.omnilink.bridge.protocol.AncsProtocol
import com.omnilink.bridge.protocol.BleChunkCodec
import com.omnilink.bridge.protocol.BridgeEnvelope
import com.omnilink.bridge.protocol.CallPhase
import com.omnilink.bridge.protocol.CallStatePayload
import com.omnilink.bridge.protocol.MessageType
import com.omnilink.bridge.protocol.NotificationPayload
import com.omnilink.bridge.protocol.OmniConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("MissingPermission")
class BleBridgeAndAncsManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val connectedCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    private var negotiatedMtu: Int = 180

    // GATT Client connection back to the connected iPhone for Apple ANCS
    private var ancsGatt: BluetoothGatt? = null
    private var ancsControlPoint: BluetoothGattCharacteristic? = null
    private val ancsReassembler = AncsDataReassembler()
    private val pendingAncsEvents = ConcurrentHashMap<Int, AncsProtocol.SourceEvent>()

    private val chunkCodec = BleChunkCodec()

    // Serialized BLE GATT Client operation queue to prevent overlapping Android GATT calls
    private val gattOpQueue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var gattAckChannel = Channel<Boolean>(Channel.CONFLATED)

    private var bondReceiverRegistered = false

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            if (device != null && state == BluetoothDevice.BOND_BONDED) {
                OmniRepository.log("蓝牙配对成功 (${device.name ?: device.address})，正在连接 Apple ANCS 通知服务...")
                connectAncsGatt(device)
            }
        }
    }

    init {
        scope.launch {
            for (op in gattOpQueue) {
                try {
                    op.invoke()
                } catch (e: Exception) {
                    OmniRepository.log("BLE 队列操作异常: ${e.message}")
                }
            }
        }
    }

    fun start() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            OmniRepository.log("蓝牙未开启，请在系统设置中打开蓝牙")
            OmniRepository.updateConnection { it.copy(bleAdvertising = false) }
            return
        }

        registerBondReceiver()
        setupGattServer()
        startBleAdvertising()
        reconnectBondedIphoneIfPresent()
    }

    fun stop() {
        unregisterBondReceiver()
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        try {
            ancsGatt?.close()
        } catch (_: Exception) {
        }
        ancsGatt = null
        ancsControlPoint = null
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        connectedCentrals.clear()
        OmniRepository.updateConnection {
            it.copy(
                bleAdvertising = false,
                bleConnectedPeer = null,
                ancsSubscribed = false
            )
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bondStateReceiver, filter)
        }
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        try {
            context.unregisterReceiver(bondStateReceiver)
        } catch (_: Exception) {
        }
        bondReceiverRegistered = false
    }

    private fun setupGattServer() {
        if (gattServer != null) return
        val server = bluetoothManager?.openGattServer(context, gattServerCallback) ?: return
        gattServer = server

        val service = BluetoothGattService(
            OmniConstants.BRIDGE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // RX: iPhone writes JSON chunks here
        val rxChar = BluetoothGattCharacteristic(
            OmniConstants.BRIDGE_RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // TX: Android notifies JSON chunks to iPhone
        val txChar = BluetoothGattCharacteristic(
            OmniConstants.BRIDGE_TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val cccd = BluetoothGattDescriptor(
            OmniConstants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        txChar.addDescriptor(cccd)
        txCharacteristic = txChar

        // AUTH: Reading this encrypted characteristic prompts iOS to pair + ask "Allow iPhone Notifications (ANCS)"
        val authChar = BluetoothGattCharacteristic(
            OmniConstants.BRIDGE_AUTH_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED
        )

        service.addCharacteristic(rxChar)
        service.addCharacteristic(txChar)
        service.addCharacteristic(authChar)
        server.addService(service)
    }

    private fun startBleAdvertising() {
        val bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (bleAdvertiser == null) {
            OmniRepository.log("当前设备不支持 BLE 外设广播，可改用同一 Wi-Fi 局域网连接")
            return
        }
        advertiser = bleAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // Keep primary advertisement <= 31 bytes (1 x 128-bit UUID = 18 bytes + flags = 21 bytes)
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(OmniConstants.BRIDGE_SERVICE_UUID))
            .build()

        // Put ANCS solicitation (API 31+) or device name in Scan Response
        val scanResponseBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scanResponseBuilder.addServiceSolicitationUuid(ParcelUuid(OmniConstants.ANCS_SERVICE_UUID))
        } else {
            scanResponseBuilder.setIncludeDeviceName(true)
        }

        try {
            bleAdvertiser.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        bleAdvertiser.startAdvertising(settings, advertiseData, scanResponseBuilder.build(), advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            OmniRepository.updateConnection { it.copy(bleAdvertising = true) }
            OmniRepository.log("BLE 双通道广播已启动，等待 iPhone 连接与配对...")
        }

        override fun onStartFailure(errorCode: Int) {
            OmniRepository.updateConnection { it.copy(bleAdvertising = false) }
            OmniRepository.log("BLE 广播启动失败 (错误码: $errorCode)")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedCentrals[device.address] = device
                val peerName = device.name ?: "iPhone (${device.address.takeLast(5)})"
                OmniRepository.updateConnection { it.copy(bleConnectedPeer = peerName) }
                OmniRepository.log("BLE 已连接设备: $peerName")

                // Send handshake greeting
                scope.launch {
                    delay(400)
                    sendOverBle(
                        BridgeEnvelope(
                            type = MessageType.HELLO,
                            deviceName = OmniRepository.localDeviceName(),
                            platform = "android"
                        )
                    )
                }

                // Connect GATT Client to discover Apple ANCS on the iPhone
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    connectAncsGatt(device)
                } else {
                    scope.launch {
                        delay(600)
                        connectAncsGatt(device)
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedCentrals.remove(device.address)
                if (connectedCentrals.isEmpty()) {
                    OmniRepository.updateConnection { it.copy(bleConnectedPeer = null) }
                }
                OmniRepository.log("BLE 设备断开: ${device.name ?: device.address}")
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            negotiatedMtu = (mtu - 3).coerceIn(20, 500)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val payload = "OMNILINK_PAIRED".toByteArray(StandardCharsets.UTF_8)
            val slice = if (offset < payload.size) payload.copyOfRange(offset, payload.size) else ByteArray(0)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)

            if (characteristic.uuid == OmniConstants.BRIDGE_AUTH_CHAR_UUID) {
                OmniRepository.log("iPhone 已完成加密认证读取，正在订阅 ANCS 系统通知...")
                connectAncsGatt(device)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (characteristic.uuid == OmniConstants.BRIDGE_RX_CHAR_UUID && value != null) {
                val completeJson = chunkCodec.decodeChunk(value)
                if (completeJson != null) {
                    BridgeEnvelope.fromJsonString(completeJson)?.let { env ->
                        if (env.deviceName.isNotBlank()) {
                            OmniRepository.updateConnection { it.copy(bleConnectedPeer = env.deviceName) }
                        }
                        OmniRepository.handleIncomingEnvelope(env, "BLE")
                    }
                }
            }
        }
    }

    fun sendOverBle(envelope: BridgeEnvelope): Boolean {
        val server = gattServer ?: return false
        val txChar = txCharacteristic ?: return false
        if (connectedCentrals.isEmpty()) return false

        val frames = chunkCodec.encode(envelope.toJsonString(), negotiatedMtu)
        scope.launch {
            for (device in connectedCentrals.values) {
                for (frame in frames) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(device, txChar, false, frame)
                        } else {
                            @Suppress("DEPRECATION")
                            run {
                                txChar.value = frame
                                server.notifyCharacteristicChanged(device, txChar, false)
                            }
                        }
                        if (frames.size > 1) delay(12)
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return true
    }

    private fun reconnectBondedIphoneIfPresent() {
        val bondedDevices = bluetoothAdapter?.bondedDevices ?: return
        val candidate = bondedDevices.firstOrNull { dev ->
            val name = dev.name.orEmpty()
            name.contains("iPhone", ignoreCase = true) || name.contains("iPad", ignoreCase = true)
        }
        if (candidate != null && ancsGatt == null) {
            OmniRepository.log("发现已配对苹果设备 ${candidate.name}，尝试自动回连 ANCS...")
            connectAncsGatt(candidate)
        }
    }

    fun connectAncsGatt(device: BluetoothDevice) {
        scope.launch {
            try {
                ancsGatt?.close()
            } catch (_: Exception) {
            }
            ancsReassembler.reset()
            ancsGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, true, ancsGattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, true, ancsGattCallback)
            }
        }
    }

    /**
     * Executes Positive (Answer=true) or Negative (Reject=false) action on iPhone ANCS Control Point.
     */
    fun performAncsCallAction(uid: Int, positive: Boolean) {
        val gatt = ancsGatt ?: return
        val cp = ancsControlPoint ?: return
        val cmdBytes = AncsProtocol.buildPerformActionCommand(uid, positive)
        enqueueGattOp {
            writeCharacteristicCompat(gatt, cp, cmdBytes)
        }
    }

    private fun enqueueGattOp(block: suspend () -> Unit) {
        gattOpQueue.trySend(block)
    }

    private suspend fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ) {
        gattAckChannel = Channel(Channel.CONFLATED)
        val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
        if (initiated) {
            withTimeoutOrNull(2500) { gattAckChannel.receive() }
        }
    }

    private suspend fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        gattAckChannel = Channel(Channel.CONFLATED)
        val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
        }
        if (initiated) {
            withTimeoutOrNull(2500) { gattAckChannel.receive() }
        }
    }

    private val ancsGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val peer = gatt.device.name ?: "iPhone"
                OmniRepository.updateConnection {
                    it.copy(bleConnectedPeer = it.bleConnectedPeer ?: peer)
                }
                gatt.requestMtu(247)
                scope.launch {
                    delay(500)
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                OmniRepository.updateConnection { it.copy(ancsSubscribed = false) }
                ancsControlPoint = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val ancsService = gatt.getService(OmniConstants.ANCS_SERVICE_UUID)
            if (ancsService == null) {
                OmniRepository.log("尚未发现 ANCS 服务：请在 iPhone 蓝牙设置中点开本机 -> 开启「共享系统通知」")
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    gatt.device.createBond()
                }
                return
            }

            val notifSource = ancsService.getCharacteristic(OmniConstants.ANCS_NOTIF_SOURCE_UUID)
            val dataSource = ancsService.getCharacteristic(OmniConstants.ANCS_DATA_SOURCE_UUID)
            ancsControlPoint = ancsService.getCharacteristic(OmniConstants.ANCS_CONTROL_POINT_UUID)

            if (notifSource == null || dataSource == null || ancsControlPoint == null) {
                OmniRepository.log("ANCS 特征值不完整")
                return
            }

            // Per Apple ANCS Specification: ALWAYS subscribe to Data Source BEFORE Notification Source!
            enqueueGattOp {
                gatt.setCharacteristicNotification(dataSource, true)
                val dsDesc = dataSource.getDescriptor(OmniConstants.CCCD_UUID)
                if (dsDesc != null) {
                    writeDescriptorCompat(gatt, dsDesc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }

                gatt.setCharacteristicNotification(notifSource, true)
                val nsDesc = notifSource.getDescriptor(OmniConstants.CCCD_UUID)
                if (nsDesc != null) {
                    writeDescriptorCompat(gatt, nsDesc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }

                OmniRepository.updateConnection { it.copy(ancsSubscribed = true) }
                OmniRepository.log("已成功订阅 iPhone ANCS 系统通知与来电通道！")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            gattAckChannel.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            gattAckChannel.trySend(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleAncsCharacteristicNotification(gatt, characteristic.uuid, value)
        }

        @Deprecated("Deprecated in API 33, required for Android 9 - 12L")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value ?: return
                handleAncsCharacteristicNotification(gatt, characteristic.uuid, bytes)
            }
        }
    }

    private fun handleAncsCharacteristicNotification(
        gatt: BluetoothGatt,
        charUuid: java.util.UUID,
        bytes: ByteArray
    ) {
        when (charUuid) {
            OmniConstants.ANCS_NOTIF_SOURCE_UUID -> {
                val ev = AncsProtocol.parseNotificationSource(bytes) ?: return
                when (ev.eventId) {
                    AncsProtocol.EVENT_ID_NOTIFICATION_REMOVED -> {
                        pendingAncsEvents.remove(ev.notificationUid)
                        val activeCall = OmniRepository.remoteIphoneCall.value
                        if (ev.categoryId == AncsProtocol.CATEGORY_INCOMING_CALL ||
                            activeCall?.ancsUid == ev.notificationUid
                        ) {
                            OmniRepository.onIphoneCallStateChanged(null)
                        }
                    }

                    AncsProtocol.EVENT_ID_NOTIFICATION_ADDED,
                    AncsProtocol.EVENT_ID_NOTIFICATION_MODIFIED -> {
                        // Skip historical notifications except active incoming calls
                        if (ev.isPreExisting && ev.categoryId != AncsProtocol.CATEGORY_INCOMING_CALL) {
                            return
                        }
                        pendingAncsEvents[ev.notificationUid] = ev
                        val cp = ancsControlPoint ?: return
                        val cmd = AncsProtocol.buildGetAttributesCommand(ev.notificationUid)
                        enqueueGattOp {
                            writeCharacteristicCompat(gatt, cp, cmd)
                        }
                    }
                }
            }

            OmniConstants.ANCS_DATA_SOURCE_UUID -> {
                val parsed = ancsReassembler.appendAndTryParse(bytes) ?: return
                val sourceEvent = pendingAncsEvents.remove(parsed.uid)
                val isIncomingCall = sourceEvent?.categoryId == AncsProtocol.CATEGORY_INCOMING_CALL ||
                    parsed.appIdentifier == "com.apple.InCallService" ||
                    (parsed.appIdentifier == "com.apple.mobilephone" &&
                        sourceEvent?.hasPositiveAction == true)

                if (isIncomingCall) {
                    val callerTitle = parsed.title.ifBlank { "iPhone 未知来电" }
                    val subtitleOrMsg = listOf(parsed.subtitle, parsed.message)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    OmniRepository.onIphoneCallStateChanged(
                        CallStatePayload(
                            callId = "ancs-${parsed.uid}",
                            phase = CallPhase.RINGING,
                            phoneNumber = subtitleOrMsg.ifBlank { "iPhone 蜂窝/FaceTime 来电" },
                            callerName = callerTitle,
                            sourcePlatform = "ios",
                            ancsUid = parsed.uid,
                            canAnswer = sourceEvent?.hasPositiveAction ?: true,
                            canReject = sourceEvent?.hasNegativeAction ?: true
                        )
                    )
                } else {
                    val combinedBody = if (parsed.subtitle.isNotBlank() && parsed.message.isNotBlank()) {
                        "${parsed.subtitle}: ${parsed.message}"
                    } else {
                        parsed.message.ifBlank { parsed.subtitle }
                    }
                    if (parsed.title.isBlank() && combinedBody.isBlank()) return
                    OmniRepository.onIphoneNotificationReceived(
                        NotificationPayload(
                            key = "ancs-${parsed.uid}",
                            appId = parsed.appIdentifier,
                            appName = parsed.appDisplayName,
                            title = parsed.title.ifBlank { parsed.appDisplayName },
                            body = combinedBody,
                            sourcePlatform = "ios"
                        )
                    )
                }
            }
        }
    }
}
