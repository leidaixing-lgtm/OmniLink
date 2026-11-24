import AVFoundation
import CallKit
import Foundation

final class CallKitBridgeManager: NSObject, CXProviderDelegate {
    private let provider: CXProvider
    private let callController = CXCallController()

    private var activeUUID: UUID?
    private var activeAndroidCallId: String?
    private var answeredLocally = false

    var onUserCallCommand: ((String, RemoteCallCommand) -> Void)?
    var onLog: ((String) -> Void)?

    override init() {
        let config = CXProviderConfiguration(localizedName: "双机互联 (安卓来电)")
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.maximumCallGroups = 1
        config.supportedHandleTypes = [.phoneNumber, .generic]
        config.includesCallsInRecents = true

        self.provider = CXProvider(configuration: config)
        super.init()
        self.provider.setDelegate(self, queue: .main)
    }

    func handleAndroidCallState(_ call: CallStatePayload, enableCallKit: Bool) {
        switch call.phase {
        case .RINGING:
            guard enableCallKit else {
                LocalNotificationManager.shared.postFallbackCallBanner(call)
                return
            }

            if activeAndroidCallId == call.callId, activeUUID != nil {
                return
            }

            let uuid = UUID()
            activeUUID = uuid
            activeAndroidCallId = call.callId
            answeredLocally = false

            let update = CXCallUpdate()
            let callerLabel = call.callerName.isEmpty ? call.phoneNumber : call.callerName
            update.localizedCallerName = "[安卓来电] \(callerLabel)"
            update.remoteHandle = CXHandle(
                type: call.phoneNumber.isEmpty ? .generic : .phoneNumber,
                value: call.phoneNumber.isEmpty ? "Android SIM" : call.phoneNumber
            )
            update.hasVideo = false
            update.supportsHolding = false
            update.supportsGrouping = false
            update.supportsUngrouping = false
            update.supportsDTMF = false

            provider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
                if let error = error {
                    self?.onLog?("CallKit 唤起失败 (\(error.localizedDescription))，已自动降级为横幅通知")
                    LocalNotificationManager.shared.postFallbackCallBanner(call)
                } else {
                    self?.onLog?("已唤起 iOS 原生 CallKit 来电界面: \(callerLabel)")
                }
            }

        case .ACTIVE:
            // If answered directly on the Android handset, close ringing banner or sync state
            LocalNotificationManager.shared.clearFallbackCallBanner()
            if !answeredLocally, let uuid = activeUUID {
                // End the CallKit ringing screen if the user picked up directly on the Android phone
                provider.reportCall(with: uuid, endedAt: Date(), reason: .answeredElsewhere)
                activeUUID = nil
                activeAndroidCallId = nil
            }

        case .ENDED:
            LocalNotificationManager.shared.clearFallbackCallBanner()
            if let uuid = activeUUID {
                provider.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
            }
            activeUUID = nil
            activeAndroidCallId = nil
            answeredLocally = false
        }
    }

    func endActiveCallLocally() {
        guard let uuid = activeUUID else { return }
        let endAction = CXEndCallAction(call: uuid)
        let transaction = CXTransaction(action: endAction)
        callController.request(transaction) { _ in }
    }

    func isManagedCallUUID(_ uuid: UUID) -> Bool {
        return activeUUID == uuid
    }

    // MARK: - CXProviderDelegate

    func providerDidReset(_ provider: CXProvider) {
        activeUUID = nil
        activeAndroidCallId = nil
        answeredLocally = false
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        answeredLocally = true
        let callId = activeAndroidCallId ?? "android-call"
        configureAudioSessionForPlaceholder()
        onUserCallCommand?(callId, .ANSWER)
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        let callId = activeAndroidCallId ?? "android-call"
        let cmd: RemoteCallCommand = answeredLocally ? .HANGUP : .REJECT
        onUserCallCommand?(callId, cmd)
        activeUUID = nil
        activeAndroidCallId = nil
        answeredLocally = false
        action.fulfill()
    }

    private func configureAudioSessionForPlaceholder() {
        // Keep session non-exclusive (.mixWithOthers) so multipoint Bluetooth headphones can switch cleanly to Android HFP
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default, options: [.mixWithOthers, .allowBluetooth])
    }
}
