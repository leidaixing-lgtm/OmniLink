package com.omnilink.bridge.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.omnilink.bridge.MainActivity
import com.omnilink.bridge.R
import com.omnilink.bridge.protocol.CallPhase
import com.omnilink.bridge.protocol.CallStatePayload
import com.omnilink.bridge.protocol.NotificationPayload
import com.omnilink.bridge.telephony.OmniTelecomConnectionService
import com.omnilink.bridge.ui.IncomingCallActivity

class RemoteAlertNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_FOREGROUND = "omnilink_foreground"
        const val CHANNEL_MIRROR_NOTIF = "omnilink_mirror_notif"
        const val CHANNEL_INCOMING_CALL = "omnilink_incoming_call_v2"

        const val FOREGROUND_NOTIF_ID = 9001
        const val INCOMING_CALL_NOTIF_ID = 9002
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var activeCallHandledBySystemInCallUi = false

    init {
        createChannels()
    }

    private fun createChannels() {
        val fgChannel = NotificationChannel(
            CHANNEL_FOREGROUND,
            "双机互联后台服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持与 iPhone 的蓝牙 ANCS 与局域网双通道实时在线"
            setShowBadge(false)
        }

        val mirrorChannel = NotificationChannel(
            CHANNEL_MIRROR_NOTIF,
            "来自 iPhone 的同步通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "实时弹出 iPhone 收到的微信、短信与应用通知"
            enableVibration(true)
        }

        val callChannel = NotificationChannel(
            CHANNEL_INCOMING_CALL,
            "来自 iPhone 的系统级来电通话",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "调用安卓系统 CallStyle 通话胶囊与锁屏来电提醒"
            enableVibration(true)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                Notification.AUDIO_ATTRIBUTES_DEFAULT
            )
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        notificationManager.createNotificationChannels(
            listOf(fgChannel, mirrorChannel, callChannel)
        )
    }

    fun buildForegroundNotification(statusSummary: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_link_notif)
            .setContentTitle("双机互联正在运行")
            .setContentText(statusSummary)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun showMirroredIphoneNotification(payload: NotificationPayload) {
        if (!canPostNotifications()) return

        val openIntent = PendingIntent.getActivity(
            context,
            payload.key.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_MIRROR_NOTIF)
            .setSmallIcon(R.drawable.ic_link_notif)
            .setSubText("来自 iPhone · ${payload.appName}")
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        val id = (payload.key.hashCode() and 0x7FFFFFFF).coerceAtLeast(10_000)
        NotificationManagerCompat.from(context).notify(id, notif)
    }

    @SuppressLint("MissingPermission")
    fun updateIphoneCallAlert(call: CallStatePayload?) {
        if (call == null || call.phase == CallPhase.ENDED) {
            activeCallHandledBySystemInCallUi = false
            OmniTelecomConnectionService.endConnectionRemotely()
            NotificationManagerCompat.from(context).cancel(INCOMING_CALL_NOTIF_ID)
            return
        }

        // 1. Hand off to Android System Telecom Framework first!
        if (call.phase == CallPhase.RINGING) {
            activeCallHandledBySystemInCallUi =
                OmniTelecomConnectionService.reportIncomingIphoneCall(context, call)
            if (activeCallHandledBySystemInCallUi) {
                // Native Android System Phone App (InCallUI) is now displaying the full-screen incoming call!
                return
            }
        } else if (call.phase == CallPhase.ACTIVE && activeCallHandledBySystemInCallUi) {
            return
        }

        if (!canPostNotifications()) return

        // 2. Render Native Android SystemUI CallStyle Plugin (Call Chip / Fluid Cloud / Heads-Up Call Card)
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            100,
            Intent(context, IncomingCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val answerIntent = PendingIntent.getBroadcast(
            context,
            101,
            Intent(context, CallActionBroadcastReceiver::class.java).apply {
                action = CallActionBroadcastReceiver.ACTION_ANSWER_IPHONE_CALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = PendingIntent.getBroadcast(
            context,
            102,
            Intent(context, CallActionBroadcastReceiver::class.java).apply {
                action = CallActionBroadcastReceiver.ACTION_REJECT_IPHONE_CALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callerPerson = Person.Builder()
            .setName("[iPhone] ${call.callerName.ifBlank { "未知来电" }}")
            .setImportant(true)
            .build()

        val callStyle = if (call.phase == CallPhase.RINGING) {
            NotificationCompat.CallStyle.forIncomingCall(callerPerson, rejectIntent, answerIntent)
        } else {
            NotificationCompat.CallStyle.forOngoingCall(callerPerson, rejectIntent)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_link_notif)
            .setContentTitle("[iPhone] ${call.callerName.ifBlank { "未知来电" }}")
            .setContentText(call.phoneNumber)
            .setStyle(callStyle)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setUsesChronometer(call.phase == CallPhase.ACTIVE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenIntent, call.phase == CallPhase.RINGING)

        try {
            NotificationManagerCompat.from(context).notify(INCOMING_CALL_NOTIF_ID, builder.build())
        } catch (_: Exception) {
            // Fallback for heavily customized ROMs if CallStyle validation fails
            val fallback = NotificationCompat.Builder(context, CHANNEL_INCOMING_CALL)
                .setSmallIcon(R.drawable.ic_link_notif)
                .setContentTitle("iPhone 来电: ${call.callerName}")
                .setContentText(call.phoneNumber)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setFullScreenIntent(fullScreenIntent, call.phase == CallPhase.RINGING)
                .addAction(0, "接听", answerIntent)
                .addAction(0, "挂断", rejectIntent)
                .build()
            NotificationManagerCompat.from(context).notify(INCOMING_CALL_NOTIF_ID, fallback)
        }
    }

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
