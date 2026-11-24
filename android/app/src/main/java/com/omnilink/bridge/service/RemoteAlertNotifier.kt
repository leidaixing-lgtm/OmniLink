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
import androidx.core.content.ContextCompat
import com.omnilink.bridge.MainActivity
import com.omnilink.bridge.R
import com.omnilink.bridge.protocol.CallPhase
import com.omnilink.bridge.protocol.CallStatePayload
import com.omnilink.bridge.protocol.NotificationPayload
import com.omnilink.bridge.ui.IncomingCallActivity

class RemoteAlertNotifier(private val context: Context) {

    companion object {
        const val CHANNEL_FOREGROUND = "omnilink_foreground"
        const val CHANNEL_MIRROR_NOTIF = "omnilink_mirror_notif"
        const val CHANNEL_INCOMING_CALL = "omnilink_incoming_call"

        const val FOREGROUND_NOTIF_ID = 9001
        const val INCOMING_CALL_NOTIF_ID = 9002
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
            "来自 iPhone 的来电提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "iPhone 来电全屏弹窗与一键接听/挂断控制"
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
            NotificationManagerCompat.from(context).cancel(INCOMING_CALL_NOTIF_ID)
            return
        }
        if (!canPostNotifications()) return

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

        val title = if (call.phase == CallPhase.RINGING) {
            "iPhone 来电: ${call.callerName}"
        } else {
            "iPhone 通话已接通: ${call.callerName}"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_INCOMING_CALL)
            .setSmallIcon(R.drawable.ic_link_notif)
            .setContentTitle(title)
            .setContentText(call.phoneNumber)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenIntent, call.phase == CallPhase.RINGING)

        if (call.phase == CallPhase.RINGING) {
            builder.addAction(0, "接听 iPhone 来电", answerIntent)
            builder.addAction(0, "挂断拒接", rejectIntent)
        } else {
            builder.addAction(0, "结束/挂断", rejectIntent)
        }

        NotificationManagerCompat.from(context).notify(INCOMING_CALL_NOTIF_ID, builder.build())

        // Also proactively open full-screen overlay when unlocked/foreground allowed
        if (call.phase == CallPhase.RINGING) {
            try {
                context.startActivity(
                    Intent(context, IncomingCallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                )
            } catch (_: Exception) {
            }
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
