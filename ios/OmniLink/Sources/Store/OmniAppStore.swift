import CallKit
import Combine
import Foundation
import UIKit

@MainActor
final class OmniAppStore: NSObject, ObservableObject, CXCallObserverDelegate {
    @Published var bleStatusText: String = "正在初始化蓝牙..."
    @Published var isBleConnected: Bool = false
    @Published var blePeerName: String?
    @Published var isAncsPairedReady: Bool = false

    @Published var isLanConnected: Bool = false
    @Published var lanPeerLabel: String?
    @Published var discoveredBonjourNames: [String] = []
    @Published var manualAndroidIp: String = ""

    @Published var activeAndroidCall: CallStatePayload?
    @Published var notifications: [NotificationPayload] = []
    @Published var logs: [String] = []

    // Settings persisted in UserDefaults
    @Published var enableCallKitForAndroidCalls: Bool {
        didSet { UserDefaults.standard.set(enableCallKitForAndroidCalls, forKey: "enable_callkit") }
    }
    @Published var showAndroidNotificationBanners: Bool {
        didSet { UserDefaults.standard.set(showAndroidNotificationBanners, forKey: "show_android_banners") }
    }
    @Published var forwardIosCallStateOverLanFallback: Bool {
        didSet { UserDefaults.standard.set(forwardIosCallStateOverLanFallback, forKey: "fwd_ios_call_lan") }
    }

    private let bleBridge = BleCentralBridge()
    private let lanBridge = LanBonjourBridge()
    // Lazily created after UI scene appears for maximum iOS 26/27 beta startup stability
    private var callKitBridge: CallKitBridgeManager?
    private var iosCallObserver: CXCallObserver?
    private var hasStarted = false

    private var seenMsgIds: [String] = []
    private var seenMsgSet: Set<String> = []
    private let timeFormatter: DateFormatter = {
        let df = DateFormatter()
        df.dateFormat = "HH:mm:ss"
        return df
    }()

    var isAnyChannelConnected: Bool {
        isBleConnected || isLanConnected
    }

    override init() {
        let defaults = UserDefaults.standard
        self.enableCallKitForAndroidCalls = defaults.object(forKey: "enable_callkit") as? Bool ?? true
        self.showAndroidNotificationBanners = defaults.object(forKey: "show_android_banners") as? Bool ?? true
        self.forwardIosCallStateOverLanFallback = defaults.object(forKey: "fwd_ios_call_lan") as? Bool ?? true
        self.manualAndroidIp = defaults.string(forKey: "manual_android_ip") ?? ""
        super.init()
    }

    func startAll() {
        guard !hasStarted else { return }
        hasStarted = true

        setupCallbacks()

        LocalNotificationManager.shared.configure()
        LocalNotificationManager.shared.requestAuthorization { [weak self] granted in
            Task { @MainActor in
                self?.appendLog(granted ? "iOS 本地通知横幅权限已就绪" : "请在系统设置中开启通知权限以接收安卓消息弹窗")
            }
        }

        let ck = CallKitBridgeManager()
        ck.onUserCallCommand = { [weak self] callId, cmd in
            Task { @MainActor in
                guard let self = self else { return }
                let env = BridgeEnvelope(
                    type: .CALL_ACTION,
                    deviceName: UIDevice.current.name,
                    platform: "ios",
                    callAction: CallActionPayload(callId: callId, command: cmd)
                )
                self.sendDualChannel(env)
                self.appendLog("CallKit 触发远程控制安卓电话: \(cmd.rawValue)")
                if cmd == .REJECT || cmd == .HANGUP {
                    self.activeAndroidCall = nil
                } else if cmd == .ANSWER, var current = self.activeAndroidCall {
                    current.phase = .ACTIVE
                    self.activeAndroidCall = current
                }
            }
        }
        ck.onLog = { [weak self] msg in
            Task { @MainActor in
                self?.appendLog(msg)
            }
        }
        self.callKitBridge = ck

        let observer = CXCallObserver()
        observer.setDelegate(self, queue: .main)
        self.iosCallObserver = observer

        bleBridge.start()
        lanBridge.start()

        if !manualAndroidIp.isEmpty {
            lanBridge.connectManualIp(manualAndroidIp)
        }
    }

    func rescanAndReconnect() {
        bleBridge.rescan()
        lanBridge.start()
        if !manualAndroidIp.isEmpty {
            lanBridge.connectManualIp(manualAndroidIp)
        }
    }

    func connectManualIp() {
        let clean = manualAndroidIp.trimmingCharacters(in: .whitespacesAndNewlines)
        UserDefaults.standard.set(clean, forKey: "manual_android_ip")
        lanBridge.connectManualIp(clean)
    }

    func connectBonjourService(_ name: String) {
        lanBridge.connectDiscoveredService(named: name)
    }

    func requestSystemAncsPairing() {
        bleBridge.triggerSystemPairingAndAncs()
    }

    func sendCallCommandToAndroid(_ command: RemoteCallCommand) {
        let callId = activeAndroidCall?.callId ?? "android-call"
        let env = BridgeEnvelope(
            type: .CALL_ACTION,
            deviceName: UIDevice.current.name,
            platform: "ios",
            callAction: CallActionPayload(callId: callId, command: command)
        )
        sendDualChannel(env)
        appendLog("已发送远程电话控制指令至安卓: \(command.rawValue)")

        switch command {
        case .ANSWER:
            if var current = activeAndroidCall {
                current.phase = .ACTIVE
                activeAndroidCall = current
            }
        case .REJECT, .HANGUP:
            callKitBridge?.endActiveCallLocally()
            activeAndroidCall = nil
        case .SPEAKER_ON, .SPEAKER_OFF:
            break
        }
    }

    func sendSimulatedIosNotification() {
        let payload = NotificationPayload(
            key: "ios-sim-\(Int(Date().timeIntervalSince1970))",
            appId: "com.tencent.xin",
            appName: "微信 (iPhone测试)",
            title: "iPhone 互联测试",
            body: "这是一条从 iPhone 发往安卓手机的实时同步测试通知！",
            timestamp: Int64(Date().timeIntervalSince1970 * 1000),
            sourcePlatform: "ios"
        )
        let env = BridgeEnvelope(
            type: .NOTIFICATION_POSTED,
            deviceName: UIDevice.current.name,
            platform: "ios",
            notification: payload
        )
        sendDualChannel(env)
        appendLog("已向安卓发送模拟测试通知")
    }

    func sendSimulatedIosIncomingCall() {
        let call = CallStatePayload(
            callId: "ios-sim-call-\(Int(Date().timeIntervalSince1970))",
            phase: .RINGING,
            phoneNumber: "139-0013-9000",
            callerName: "模拟 iPhone 来电",
            sourcePlatform: "ios"
        )
        let env = BridgeEnvelope(
            type: .CALL_STATE,
            deviceName: UIDevice.current.name,
            platform: "ios",
            callState: call
        )
        sendDualChannel(env)
        appendLog("已向安卓发送模拟来电提醒")
    }

    func clearNotifications() {
        notifications.removeAll()
    }

    // MARK: - Internal Wiring (Strict MainActor Hop for Swift 6 / iOS 27 Concurrency Safety)

    private func setupCallbacks() {
        bleBridge.onStateUpdated = { [weak self] statusText, connected, peer in
            Task { @MainActor in
                guard let self = self else { return }
                self.bleStatusText = statusText
                self.isBleConnected = connected
                if let peer = peer {
                    self.blePeerName = peer
                } else if !connected {
                    self.blePeerName = nil
                }
            }
        }

        bleBridge.onPairingAuthCompleted = { [weak self] in
            Task { @MainActor in
                self?.isAncsPairedReady = true
            }
        }

        bleBridge.onEnvelopeReceived = { [weak self] env in
            Task { @MainActor in
                self?.handleIncomingEnvelope(env, via: "BLE")
            }
        }

        bleBridge.onLog = { [weak self] msg in
            Task { @MainActor in
                self?.appendLog(msg)
            }
        }

        lanBridge.onConnectionChanged = { [weak self] connected, label in
            Task { @MainActor in
                self?.isLanConnected = connected
                self?.lanPeerLabel = label
            }
        }

        lanBridge.onDiscoveredServicesChanged = { [weak self] names in
            Task { @MainActor in
                self?.discoveredBonjourNames = names
            }
        }

        lanBridge.onEnvelopeReceived = { [weak self] env in
            Task { @MainActor in
                self?.handleIncomingEnvelope(env, via: "Wi-Fi")
            }
        }

        lanBridge.onLog = { [weak self] msg in
            Task { @MainActor in
                self?.appendLog(msg)
            }
        }

        LocalNotificationManager.shared.onCallNotificationAction = { [weak self] _, cmd in
            Task { @MainActor in
                self?.sendCallCommandToAndroid(cmd)
            }
        }
    }

    private func sendDualChannel(_ envelope: BridgeEnvelope) {
        _ = lanBridge.sendOverLan(envelope)
        _ = bleBridge.sendOverBle(envelope)
    }

    private func handleIncomingEnvelope(_ env: BridgeEnvelope, via channel: String) {
        guard markSeen(env.msgId) else { return }

        if !env.deviceName.isEmpty {
            if channel == "BLE" {
                blePeerName = env.deviceName
            } else {
                lanPeerLabel = env.deviceName
            }
        }

        switch env.type {
        case .HELLO:
            appendLog("通过 \(channel) 握手成功: \(env.deviceName)")

        case .PING:
            let pong = BridgeEnvelope(
                type: .PONG,
                deviceName: UIDevice.current.name,
                platform: "ios"
            )
            sendDualChannel(pong)

        case .PONG:
            break

        case .NOTIFICATION_POSTED:
            guard let notif = env.notification else { return }
            notifications.removeAll { $0.key == notif.key && $0.title == notif.title && $0.body == notif.body }
            notifications.insert(notif, at: 0)
            if notifications.count > 120 {
                notifications = Array(notifications.prefix(120))
            }
            if showAndroidNotificationBanners {
                LocalNotificationManager.shared.postMirroredAndroidNotification(notif)
            }
            appendLog("收到安卓通知 [\(notif.appName)]: \(notif.title)")

        case .NOTIFICATION_REMOVED:
            if let key = env.removedKey {
                LocalNotificationManager.shared.removeMirroredNotification(key: key)
            }

        case .CALL_STATE:
            guard let call = env.callState else { return }
            if call.phase == .ENDED {
                activeAndroidCall = nil
            } else {
                activeAndroidCall = call
            }
            if let ck = callKitBridge {
                ck.handleAndroidCallState(call, enableCallKit: enableCallKitForAndroidCalls)
            } else {
                LocalNotificationManager.shared.postFallbackCallBanner(call)
            }
            appendLog("安卓通话状态: \(call.phase.rawValue) (\(call.callerName))")

        case .CALL_ACTION:
            break
        }
    }

    private func markSeen(_ msgId: String) -> Bool {
        if seenMsgSet.contains(msgId) { return false }
        seenMsgSet.insert(msgId)
        seenMsgIds.append(msgId)
        if seenMsgIds.count > 256, let oldest = seenMsgIds.first {
            seenMsgIds.removeFirst()
            seenMsgSet.remove(oldest)
        }
        return true
    }

    func appendLog(_ message: String) {
        let stamp = timeFormatter.string(from: Date())
        logs.insert("[\(stamp)] \(message)", at: 0)
        if logs.count > 80 {
            logs = Array(logs.prefix(80))
        }
    }

    // MARK: - CXCallObserverDelegate

    nonisolated func callObserver(_ callObserver: CXCallObserver, callChanged call: CXCall) {
        let callUuid = call.uuid
        let uuidStr = callUuid.uuidString
        let hasEnded = call.hasEnded
        let hasConnected = call.hasConnected
        let isOutgoing = call.isOutgoing

        Task { @MainActor in
            if let ck = self.callKitBridge, ck.isManagedCallUUID(callUuid) {
                return
            }
            guard self.forwardIosCallStateOverLanFallback, !isOutgoing else { return }
            guard !self.isAncsPairedReady else { return }

            let phase: CallPhase
            if hasEnded {
                phase = .ENDED
            } else if hasConnected {
                phase = .ACTIVE
            } else {
                phase = .RINGING
            }

            let payload = CallStatePayload(
                callId: "ios-cx-\(uuidStr)",
                phase: phase,
                phoneNumber: "请配对蓝牙 ANCS 显示来电号码与一键接听",
                callerName: "iPhone 正在来电",
                sourcePlatform: "ios",
                canAnswer: false,
                canReject: false
            )
            let env = BridgeEnvelope(
                type: .CALL_STATE,
                deviceName: UIDevice.current.name,
                platform: "ios",
                callState: payload
            )
            _ = self.lanBridge.sendOverLan(env)
        }
    }
}
