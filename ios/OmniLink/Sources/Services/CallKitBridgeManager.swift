import AudioToolbox
import AVFoundation
import CallKit
import Foundation
import LiveCommunicationKit

/// Dual-engine iOS System Incoming Call Coordinator:
/// 1. Primary (iOS 17.4+ / iOS 26 / iOS 27, works on Mainland China CH/A & global iPhones):
///    `LiveCommunicationKit.ConversationManager` — wakes black lock screen with native iOS incoming call UI & ringtone!
/// 2. Secondary Fallback: `CallKit.CXProvider` (for iOS 16 or non-LCK environments).
/// 3. Tertiary Fallback: Audible ringtone vibration loop + Time-Sensitive interactive notification banner.
final class CallKitBridgeManager: NSObject, CXProviderDelegate {
    private let cxProvider: CXProvider
    private let cxCallController = CXCallController()
    private var lckBox: AnyObject?

    private var activeUUID: UUID?
    private var activeAndroidCallId: String?
    private var activeEngine: ActiveEngine = .none
    private var answeredLocally = false
    private var fallbackRingTimer: Timer?

    private enum ActiveEngine {
        case none
        case liveCommunicationKit
        case callKit
        case bannerFallback
    }

    var onUserCallCommand: ((String, RemoteCallCommand) -> Void)?
    var onLog: ((String) -> Void)?

    override init() {
        let cxConfig = CXProviderConfiguration(localizedName: "双机互联 (安卓来电)")
        cxConfig.supportsVideo = false
        cxConfig.maximumCallsPerCallGroup = 1
        cxConfig.maximumCallGroups = 1
        cxConfig.supportedHandleTypes = [.phoneNumber, .generic]
        cxConfig.includesCallsInRecents = true
        self.cxProvider = CXProvider(configuration: cxConfig)

        super.init()
        self.cxProvider.setDelegate(self, queue: .main)

        if #available(iOS 17.4, *) {
            let coordinator = LckConversationCoordinator()
            coordinator.owner = self
            self.lckBox = coordinator
        }
    }

    func isManagedCallUUID(_ uuid: UUID) -> Bool {
        return activeUUID == uuid
    }

    func handleAndroidCallState(_ call: CallStatePayload, enableSystemCallUI: Bool) {
        switch call.phase {
        case .RINGING:
            guard enableSystemCallUI else {
                presentAudibleBannerFallback(call)
                return
            }

            if activeAndroidCallId == call.callId, activeUUID != nil {
                return
            }

            stopFallbackRinging()
            let uuid = UUID()
            activeUUID = uuid
            activeAndroidCallId = call.callId
            answeredLocally = false

            // Prefer LiveCommunicationKit on iOS 17.4+ (specifically engineered by Apple to work on Mainland China CH/A iPhones!)
            if #available(iOS 17.4, *), let lck = lckBox as? LckConversationCoordinator {
                BackgroundKeepAliveManager.shared.pauseForSystemCall()
                Task { @MainActor in
                    let ok = await lck.reportIncomingCall(uuid: uuid, call: call)
                    if ok {
                        self.activeEngine = .liveCommunicationKit
                        self.onLog?("已唤起 iOS 系统原生电话界面 (LiveCommunicationKit): \(call.callerName)")
                    } else {
                        // Try classic CallKit next
                        self.tryReportViaCallKit(uuid: uuid, call: call)
                    }
                }
            } else {
                tryReportViaCallKit(uuid: uuid, call: call)
            }

        case .ACTIVE:
            stopFallbackRinging()
            LocalNotificationManager.shared.clearFallbackCallBanner()
            if !answeredLocally, let uuid = activeUUID {
                // Answered directly on the Android handset -> dismiss ringing screen on iPhone
                endSystemCallRemotely(uuid: uuid, answeredElsewhere: true)
            }

        case .ENDED:
            stopFallbackRinging()
            LocalNotificationManager.shared.clearFallbackCallBanner()
            if let uuid = activeUUID {
                endSystemCallRemotely(uuid: uuid, answeredElsewhere: false)
            }
            BackgroundKeepAliveManager.shared.resumeAfterSystemCall()
        }
    }

    func endActiveCallLocally() {
        stopFallbackRinging()
        guard let uuid = activeUUID else { return }

        switch activeEngine {
        case .liveCommunicationKit:
            if #available(iOS 17.4, *), let lck = lckBox as? LckConversationCoordinator {
                Task {
                    await lck.endLocally(uuid: uuid)
                }
            }
        case .callKit:
            let endAction = CXEndCallAction(call: uuid)
            let transaction = CXTransaction(action: endAction)
            cxCallController.request(transaction) { _ in }
        case .bannerFallback, .none:
            LocalNotificationManager.shared.clearFallbackCallBanner()
        }
        resetCallTracking()
        BackgroundKeepAliveManager.shared.resumeAfterSystemCall()
    }

    // MARK: - Internal Helpers

    private func tryReportViaCallKit(uuid: UUID, call: CallStatePayload) {
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

        cxProvider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                self.onLog?("系统 CallKit 受限 (\(error.localizedDescription))，已启用强振铃通知兜底")
                BackgroundKeepAliveManager.shared.resumeAfterSystemCall()
                self.presentAudibleBannerFallback(call)
            } else {
                self.activeEngine = .callKit
                self.onLog?("已唤起 iOS 原生 CallKit 来电界面: \(callerLabel)")
            }
        }
    }

    private func presentAudibleBannerFallback(_ call: CallStatePayload) {
        activeEngine = .bannerFallback
        LocalNotificationManager.shared.postFallbackCallBanner(call)
        startFallbackRinging()
    }

    private func startFallbackRinging() {
        stopFallbackRinging()
        AudioServicesPlayAlertSound(SystemSoundID(kSystemSoundID_Vibrate))
        AudioServicesPlaySystemSound(1005)
        fallbackRingTimer = Timer.scheduledTimer(withTimeInterval: 3.0, repeats: true) { _ in
            AudioServicesPlayAlertSound(SystemSoundID(kSystemSoundID_Vibrate))
            AudioServicesPlaySystemSound(1005)
        }
    }

    private func stopFallbackRinging() {
        fallbackRingTimer?.invalidate()
        fallbackRingTimer = nil
    }

    private func endSystemCallRemotely(uuid: UUID, answeredElsewhere: Bool) {
        switch activeEngine {
        case .liveCommunicationKit:
            if #available(iOS 17.4, *), let lck = lckBox as? LckConversationCoordinator {
                lck.reportRemoteEnded(uuid: uuid, joinedElsewhere: answeredElsewhere)
            }
        case .callKit:
            cxProvider.reportCall(
                with: uuid,
                endedAt: Date(),
                reason: answeredElsewhere ? .answeredElsewhere : .remoteEnded
            )
        case .bannerFallback, .none:
            break
        }
        resetCallTracking()
    }

    fileprivate func notifyUserAccepted() {
        stopFallbackRinging()
        answeredLocally = true
        let callId = activeAndroidCallId ?? "android-call"
        configureNonExclusiveAudioSession()
        onUserCallCommand?(callId, .ANSWER)
    }

    fileprivate func notifyUserEnded() {
        stopFallbackRinging()
        let callId = activeAndroidCallId ?? "android-call"
        let cmd: RemoteCallCommand = answeredLocally ? .HANGUP : .REJECT
        onUserCallCommand?(callId, cmd)
        resetCallTracking()
        BackgroundKeepAliveManager.shared.resumeAfterSystemCall()
    }

    fileprivate func resetCallTracking() {
        activeUUID = nil
        activeAndroidCallId = nil
        activeEngine = .none
        answeredLocally = false
    }

    fileprivate func configureNonExclusiveAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default, options: [.mixWithOthers, .allowBluetooth])
    }

    // MARK: - CXProviderDelegate

    func providerDidReset(_ provider: CXProvider) {
        stopFallbackRinging()
        resetCallTracking()
        BackgroundKeepAliveManager.shared.resumeAfterSystemCall()
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        notifyUserAccepted()
        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        notifyUserEnded()
        action.fulfill()
    }
}

// MARK: - iOS 17.4+ LiveCommunicationKit Native System Incoming Call Coordinator

@available(iOS 17.4, *)
private final class LckConversationCoordinator: NSObject, ConversationManagerDelegate {
    weak var owner: CallKitBridgeManager?
    private let manager: ConversationManager

    override init() {
        let config = ConversationManager.Configuration(
            ringtoneName: nil,
            iconTemplateImageData: nil,
            maximumConversationGroups: 1,
            maximumConversationsPerConversationGroup: 1,
            includesConversationInRecents: true,
            supportsVideo: false,
            supportedHandleTypes: [.phoneNumber, .generic]
        )
        self.manager = ConversationManager(configuration: config)
        super.init()
        self.manager.delegate = self
    }

    func reportIncomingCall(uuid: UUID, call: CallStatePayload) async -> Bool {
        let callerLabel = call.callerName.isEmpty ? call.phoneNumber : call.callerName
        let remoteHandle = LiveCommunicationKit.Handle(
            type: call.phoneNumber.isEmpty ? .generic : .phoneNumber,
            value: call.phoneNumber.isEmpty ? "Android SIM" : call.phoneNumber,
            displayName: "[安卓来电] \(callerLabel)"
        )
        let update = Conversation.Update(
            localMember: nil,
            members: [remoteHandle],
            activeRemoteMembers: [remoteHandle],
            capabilities: [.playingTones]
        )

        do {
            try await manager.reportNewIncomingConversation(uuid: uuid, update: update)
            return true
        } catch {
            owner?.onLog?("LiveCommunicationKit 返回提示: \(error.localizedDescription)")
            return false
        }
    }

    func reportRemoteEnded(uuid: UUID, joinedElsewhere: Bool) {
        if let conv = manager.conversations.first(where: { $0.uuid == uuid }) {
            let reason: Conversation.EndedReason = joinedElsewhere ? .joinedElsewhere : .remoteEnded
            manager.reportConversationEvent(.conversationEnded(Date(), reason), for: conv)
        }
    }

    func endLocally(uuid: UUID) async {
        let endAction = EndConversationAction(conversationUUID: uuid)
        try? await manager.perform([endAction])
    }

    // MARK: - ConversationManagerDelegate

    func conversationManager(_ manager: ConversationManager, conversationChanged conversation: Conversation) {
        if conversation.state == .left {
            BackgroundKeepAliveManager.shared.resumeAfterSystemCall()
        }
    }

    func conversationManagerDidBegin(_ manager: ConversationManager) {}

    func conversationManagerDidReset(_ manager: ConversationManager) {
        owner?.resetCallTracking()
        BackgroundKeepAliveManager.shared.resumeAfterSystemCall()
    }

    func conversationManager(_ manager: ConversationManager, perform action: ConversationAction) {
        if let joinAction = action as? JoinConversationAction {
            owner?.notifyUserAccepted()
            joinAction.fulfill(dateConnected: Date())
        } else if let endAction = action as? EndConversationAction {
            owner?.notifyUserEnded()
            endAction.fulfill(dateEnded: Date())
        } else {
            action.fulfill()
        }
    }

    func conversationManager(_ manager: ConversationManager, timedOutPerforming action: ConversationAction) {
        action.fail()
    }

    func conversationManager(_ manager: ConversationManager, didActivate audioSession: AVAudioSession) {
        owner?.configureNonExclusiveAudioSession()
    }

    func conversationManager(_ manager: ConversationManager, didDeactivate audioSession: AVAudioSession) {
        BackgroundKeepAliveManager.shared.resumeAfterSystemCall()
    }
}
