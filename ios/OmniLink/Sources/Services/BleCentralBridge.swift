import CoreBluetooth
import Foundation
import UIKit

final class BleCentralBridge: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private var centralManager: CBCentralManager!
    private var connectedPeripheral: CBPeripheral?
    private var discoveredPeripherals: [UUID: CBPeripheral] = [:]

    private var rxCharacteristic: CBCharacteristic?
    private var txCharacteristic: CBCharacteristic?
    private var authCharacteristic: CBCharacteristic?

    private let chunkCodec = BleChunkCodec()
    private let savedPeripheralUuidKey = "omnilink_saved_ble_uuid"

    var onStateUpdated: ((String, Bool, String?) -> Void)?
    var onEnvelopeReceived: ((BridgeEnvelope) -> Void)?
    var onPairingAuthCompleted: (() -> Void)?
    var onLog: ((String) -> Void)?

    override init() {
        super.init()
    }

    func start() {
        // Defensive guard: avoid TCC crash if a third-party signer ever strips Info.plist privacy keys
        guard Bundle.main.object(forInfoDictionaryKey: "NSBluetoothAlwaysUsageDescription") != nil else {
            onStateUpdated?("签名工具移除了蓝牙权限声明", false, nil)
            onLog?("错误：当前 IPA 签名移除了 Info.plist 中的 NSBluetoothAlwaysUsageDescription，请在全能签中关闭移除权限/后台选项")
            return
        }

        if centralManager == nil {
            var options: [String: Any] = [
                CBCentralManagerOptionShowPowerAlertKey: true
            ]
            // Only pass RestoreIdentifierKey when 'bluetooth-central' exists in UIBackgroundModes
            // (CoreBluetooth throws NSInternalInconsistencyException otherwise!)
            let bgModes = (Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String]) ?? []
            if bgModes.contains("bluetooth-central") {
                options[CBCentralManagerOptionRestoreIdentifierKey] = "com.omnilink.bridge.central"
            }

            centralManager = CBCentralManager(
                delegate: self,
                queue: .main,
                options: options
            )
        } else if centralManager.state == .poweredOn {
            startScanningAndReconnect()
        }
    }

    func rescan() {
        guard centralManager?.state == .poweredOn else {
            onLog?("蓝牙尚未就绪，请检查系统蓝牙与 App 蓝牙权限")
            return
        }
        startScanningAndReconnect()
    }

    /// Triggers reading the encrypted characteristic on Android, prompting iOS Pairing + ANCS permission dialog.
    func triggerSystemPairingAndAncs() {
        guard let peripheral = connectedPeripheral, let authChar = authCharacteristic else {
            onLog?("尚未连接安卓 BLE 服务，请等待连接成功后再试")
            return
        }
        onLog?("正在请求蓝牙加密认证以触发 iOS 系统配对与「共享系统通知 (ANCS)」弹窗...")
        peripheral.readValue(for: authChar)
    }

    func sendOverBle(_ envelope: BridgeEnvelope) -> Bool {
        guard let peripheral = connectedPeripheral,
              peripheral.state == .connected,
              let rxChar = rxCharacteristic,
              let json = envelope.toJsonString()
        else {
            return false
        }

        let writeType: CBCharacteristicWriteType =
            rxChar.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        let maxWrite = max(20, min(240, peripheral.maximumWriteValueLength(for: writeType)))
        let frames = chunkCodec.encode(json: json, maxChunkBytes: maxWrite)

        for frame in frames {
            peripheral.writeValue(frame, for: rxChar, type: writeType)
        }
        return true
    }

    private func startScanningAndReconnect() {
        // 1. Try retrieving known peripheral first for instant background-friendly reconnect
        if let uuidStr = UserDefaults.standard.string(forKey: savedPeripheralUuidKey),
           let uuid = UUID(uuidString: uuidStr) {
            let known = centralManager.retrievePeripherals(withIdentifiers: [uuid])
            if let target = known.first {
                discoveredPeripherals[target.identifier] = target
                if target.state != .connected && target.state != .connecting {
                    connectPeripheral(target)
                }
            }
        }

        // 2. Also check already-connected peripherals exposing our Bridge Service
        let connected = centralManager.retrieveConnectedPeripherals(
            withServices: [OmniConstants.bridgeServiceUUID]
        )
        if let existing = connected.first {
            discoveredPeripherals[existing.identifier] = existing
            connectPeripheral(existing)
        }

        // 3. Scan explicitly for BRIDGE_SERVICE_UUID (required for iOS background BLE scanning!)
        centralManager.scanForPeripherals(
            withServices: [OmniConstants.bridgeServiceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        publishStatus()
        onLog?("BLE 扫描中，正在寻找附近开启双机互联的安卓手机...")
    }

    private func connectPeripheral(_ peripheral: CBPeripheral) {
        peripheral.delegate = self
        connectedPeripheral = peripheral
        centralManager.connect(
            peripheral,
            options: [
                CBConnectPeripheralOptionNotifyOnConnectionKey: true,
                CBConnectPeripheralOptionNotifyOnDisconnectionKey: true
            ]
        )
    }

    private func publishStatus() {
        let stateDesc: String
        switch centralManager?.state {
        case .poweredOn:
            if let p = connectedPeripheral, p.state == .connected {
                stateDesc = "BLE 已连接"
            } else {
                stateDesc = "BLE 正在搜索安卓设备..."
            }
        case .poweredOff:
            stateDesc = "系统蓝牙已关闭"
        case .unauthorized:
            stateDesc = "未授予蓝牙权限"
        default:
            stateDesc = "蓝牙初始化中..."
        }
        let isConnected = connectedPeripheral?.state == .connected
        let peerName = isConnected ? (connectedPeripheral?.name ?? "Android 设备") : nil
        onStateUpdated?(stateDesc, isConnected, peerName)
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        publishStatus()
        if central.state == .poweredOn {
            startScanningAndReconnect()
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral],
           let restored = peripherals.first {
            restored.delegate = self
            connectedPeripheral = restored
            discoveredPeripherals[restored.identifier] = restored
            if restored.state == .connected {
                restored.discoverServices([OmniConstants.bridgeServiceUUID])
            }
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        discoveredPeripherals[peripheral.identifier] = peripheral
        if connectedPeripheral == nil || connectedPeripheral?.state == .disconnected {
            onLog?("发现安卓互联设备: \(peripheral.name ?? peripheral.identifier.uuidString.prefix(8).description)")
            connectPeripheral(peripheral)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        UserDefaults.standard.set(peripheral.identifier.uuidString, forKey: savedPeripheralUuidKey)
        central.stopScan()
        connectedPeripheral = peripheral
        peripheral.delegate = self
        publishStatus()
        onLog?("BLE 已连接安卓设备，正在发现服务特征...")
        peripheral.discoverServices([OmniConstants.bridgeServiceUUID])
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        onLog?("BLE 连接失败: \(error?.localizedDescription ?? "未知原因")")
        publishStatus()
        startScanningAndReconnect()
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        rxCharacteristic = nil
        txCharacteristic = nil
        authCharacteristic = nil
        publishStatus()
        onLog?("BLE 连接已断开，保持后台自动重连挂起...")
        // Calling connect() on disconnected peripheral arms zero-power iOS hardware auto-reconnect
        connectPeripheral(peripheral)
    }

    // MARK: - CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }
        for service in services where service.uuid == OmniConstants.bridgeServiceUUID {
            peripheral.discoverCharacteristics(
                [
                    OmniConstants.bridgeRxCharUUID,
                    OmniConstants.bridgeTxCharUUID,
                    OmniConstants.bridgeAuthCharUUID
                ],
                for: service
            )
        }
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        guard let chars = service.characteristics else { return }
        for ch in chars {
            switch ch.uuid {
            case OmniConstants.bridgeRxCharUUID:
                rxCharacteristic = ch
            case OmniConstants.bridgeTxCharUUID:
                txCharacteristic = ch
                peripheral.setNotifyValue(true, for: ch)
            case OmniConstants.bridgeAuthCharUUID:
                authCharacteristic = ch
                // Automatically read encrypted characteristic to prompt iOS Bluetooth Pairing + ANCS sharing!
                peripheral.readValue(for: ch)
            default:
                break
            }
        }

        // Send HELLO handshake with iPhone device name
        let hello = BridgeEnvelope(
            type: .HELLO,
            deviceName: UIDevice.current.name,
            platform: "ios"
        )
        _ = sendOverBle(hello)
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        if characteristic.uuid == OmniConstants.bridgeAuthCharUUID {
            if error == nil {
                onPairingAuthCompleted?()
                onLog?("iOS 蓝牙加密配对已就绪（ANCS 通知共享已激活）")
            } else {
                onLog?("配对校验提示: \(error?.localizedDescription ?? "请在弹窗中点击配对")")
            }
            return
        }

        guard characteristic.uuid == OmniConstants.bridgeTxCharUUID,
              let data = characteristic.value
        else {
            return
        }

        if let fullJson = chunkCodec.decodeChunk(data),
           let env = BridgeEnvelope.fromJsonString(fullJson) {
            onEnvelopeReceived?(env)
        }
    }
}
