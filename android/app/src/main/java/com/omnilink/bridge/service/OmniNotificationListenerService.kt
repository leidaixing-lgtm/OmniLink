package com.omnilink.bridge.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.omnilink.bridge.data.OmniRepository
import com.omnilink.bridge.protocol.NotificationPayload

class OmniNotificationListenerService : NotificationListenerService() {

    private val ignoredSystemPackages = setOf(
        "android",
        "com.android.systemui"
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        OmniRepository.log("安卓系统通知监听服务已连接")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        val pkg = item.packageName ?: return

        // Prevent infinite loop: never mirror OmniLink's own notifications
        if (pkg == packageName || ignoredSystemPackages.contains(pkg)) return

        val notification = item.notification ?: return
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (isOngoing && OmniRepository.settings.value.ignoreOngoingNotifications) {
            return
        }

        // Skip group summary headers that duplicate child notifications
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return
        }

        // Skip incoming call notification if call state forwarding already handles it
        if (notification.category == Notification.CATEGORY_CALL &&
            OmniRepository.settings.value.forwardAndroidCalls
        ) {
            return
        }

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val body = bigText.ifBlank { normalText }

        if (title.isBlank() && body.isBlank()) return

        val appLabel = resolveAppLabel(pkg)
        OmniRepository.onAndroidNotificationPosted(
            NotificationPayload(
                key = item.key ?: "$pkg-${item.id}",
                appId = pkg,
                appName = appLabel,
                title = title.ifBlank { appLabel },
                body = body,
                timestamp = item.postTime,
                sourcePlatform = "android"
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        if (sbn.packageName == packageName) return
        OmniRepository.onAndroidNotificationRemoved(key)
    }

    private fun resolveAppLabel(pkg: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
    }
}
