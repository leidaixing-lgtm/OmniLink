import Foundation
import UserNotifications

final class LocalNotificationManager: NSObject, UNUserNotificationCenterDelegate {
    static let shared = LocalNotificationManager()

    static let callCategoryIdentifier = "OMNILINK_ANDROID_CALL"
    static let actionAnswerIdentifier = "OMNILINK_ACTION_ANSWER"
    static let actionRejectIdentifier = "OMNILINK_ACTION_REJECT"

    var onCallNotificationAction: ((String, RemoteCallCommand) -> Void)?

    private override init() {
        super.init()
    }

    func configure() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self

        let answerAction = UNNotificationAction(
            identifier: Self.actionAnswerIdentifier,
            title: "接听安卓来电",
            options: [.authenticationRequired]
        )
        let rejectAction = UNNotificationAction(
            identifier: Self.actionRejectIdentifier,
            title: "挂断拒接",
            options: [.destructive]
        )

        let callCategory = UNNotificationCategory(
            identifier: Self.callCategoryIdentifier,
            actions: [answerAction, rejectAction],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        center.setNotificationCategories([callCategory])
    }

    func requestAuthorization(completion: ((Bool) -> Void)? = nil) {
        UNUserNotificationCenter.current().requestAuthorization(
            options: [.alert, .sound, .badge]
        ) { granted, _ in
            DispatchQueue.main.async {
                completion?(granted)
            }
        }
    }

    func postMirroredAndroidNotification(_ item: NotificationPayload) {
        let content = UNMutableNotificationContent()
        content.title = "[\(item.appName)] \(item.title)"
        content.body = item.body.isEmpty ? "收到来自安卓手机的新消息" : item.body
        content.sound = .default
        if #available(iOS 15.0, *) {
            content.interruptionLevel = .timeSensitive
        }

        let request = UNNotificationRequest(
            identifier: "mirror-\(item.key)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    func removeMirroredNotification(key: String) {
        let id = "mirror-\(key)"
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id])
    }

    func postFallbackCallBanner(_ call: CallStatePayload) {
        let content = UNMutableNotificationContent()
        content.title = "安卓手机来电: \(call.callerName)"
        content.subtitle = call.phoneNumber
        content.body = "长按通知可直接控制接听或挂断安卓来电"
        content.sound = .default
        content.categoryIdentifier = Self.callCategoryIdentifier
        content.userInfo = ["callId": call.callId]
        if #available(iOS 15.0, *) {
            content.interruptionLevel = .timeSensitive
        }

        let request = UNNotificationRequest(
            identifier: "android-call-banner",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }

    func clearFallbackCallBanner() {
        UNUserNotificationCenter.current().removeDeliveredNotifications(
            withIdentifiers: ["android-call-banner"]
        )
    }

    // Show banners even while OmniLink is open in foreground
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let callId = (response.notification.request.content.userInfo["callId"] as? String) ?? "android-call"
        let actionId = response.actionIdentifier
        DispatchQueue.main.async { [weak self] in
            switch actionId {
            case Self.actionAnswerIdentifier:
                self?.onCallNotificationAction?(callId, .ANSWER)
            case Self.actionRejectIdentifier:
                self?.onCallNotificationAction?(callId, .REJECT)
            default:
                break
            }
            completionHandler()
        }
    }
}
