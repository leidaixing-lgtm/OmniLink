package com.omnilink.bridge.data

import android.content.Context
import android.os.Build
import com.omnilink.bridge.protocol.BridgeEnvelope
import com.omnilink.bridge.protocol.CallActionPayload
import com.omnilink.bridge.protocol.CallPhase
import com.omnilink.bridge.protocol.CallStatePayload
import com.omnilink.bridge.protocol.MessageType
import com.omnilink.bridge.protocol.NotificationPayload
import com.omnilink.bridge.protocol.RemoteCallCommand
import com.omnilink.bridge.telephony.OmniTelecomConnectionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

data class LinkConnectionState(
    val isServiceRunning: Boolean = false,
    val bleAdvertising: Boolean = false,
    val bleConnectedPeer: String? = null,
    val ancsSubscribed: Boolean = false,
    val lanListening: Boolean = false,
    val lanLocalIp: String = "未连接 Wi-Fi",
    val lanConnectedPeer: String? = null,
    val manualTargetIp: String = ""
) {
    val isAnyChannelConnected: Boolean
        get() = bleConnectedPeer != null || lanConnectedPeer != null || ancsSubscribed
}

data class UserSettings(
    val forwardAndroidNotifications: Boolean = true,
    val receiveIphoneNotifications: Boolean = true,
    val forwardAndroidCalls: Boolean = true,
    val receiveIphoneCalls: Boolean = true,
    val autoSpeakerOnRemoteAnswer: Boolean = true,
    val ignoreOngoingNotifications: Boolean = true
)

object OmniRepository {
    private val _connectionState = MutableStateFlow(LinkConnectionState())
    val connectionState: StateFlow<LinkConnectionState> = _connectionState.asStateFlow()

    private val _settings = MutableStateFlow(UserSettings())
    val settings: StateFlow<UserSettings> = _settings.asStateFlow()

    // Active incoming/ongoing call from the iPhone (via ANCS or custom bridge)
    private val _remoteIphoneCall = MutableStateFlow<CallStatePayload?>(null)
    val remoteIphoneCall: StateFlow<CallStatePayload?> = _remoteIphoneCall.asStateFlow()

    // Active incoming/ongoing call on this Android phone (mirrored to iPhone)
    private val _localAndroidCall = MutableStateFlow<CallStatePayload?>(null)
    val localAndroidCall: StateFlow<CallStatePayload?> = _localAndroidCall.asStateFlow()

    // Unified timeline of mirrored notifications (both received from iPhone & forwarded to iPhone)
    private val _notifications = MutableStateFlow<List<NotificationPayload>>(emptyList())
    val notifications: StateFlow<List<NotificationPayload>> = _notifications.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val seenMsgIds = LinkedHashSet<String>()

    var outboundSender: ((BridgeEnvelope) -> Unit)? = null
    var onRemoteIphoneCallAction: ((CallStatePayload, Boolean) -> Unit)? = null
    var onLocalAndroidCallCommand: ((RemoteCallCommand) -> Unit)? = null
    var onRemoteIphoneNotificationDisplay: ((NotificationPayload) -> Unit)? = null
    var onRemoteIphoneCallAlert: ((CallStatePayload?) -> Unit)? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("omnilink_prefs", Context.MODE_PRIVATE)
        _settings.value = UserSettings(
            forwardAndroidNotifications = prefs.getBoolean("fwd_android_notif", true),
            receiveIphoneNotifications = prefs.getBoolean("rcv_iphone_notif", true),
            forwardAndroidCalls = prefs.getBoolean("fwd_android_call", true),
            receiveIphoneCalls = prefs.getBoolean("rcv_iphone_call", true),
            autoSpeakerOnRemoteAnswer = prefs.getBoolean("auto_speaker", true),
            ignoreOngoingNotifications = prefs.getBoolean("ignore_ongoing", true)
        )
        val savedIp = prefs.getString("manual_target_ip", "").orEmpty()
        _connectionState.update { it.copy(manualTargetIp = savedIp) }
    }

    fun updateSettings(context: Context, transform: (UserSettings) -> UserSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        context.getSharedPreferences("omnilink_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("fwd_android_notif", updated.forwardAndroidNotifications)
            .putBoolean("rcv_iphone_notif", updated.receiveIphoneNotifications)
            .putBoolean("fwd_android_call", updated.forwardAndroidCalls)
            .putBoolean("rcv_iphone_call", updated.receiveIphoneCalls)
            .putBoolean("auto_speaker", updated.autoSpeakerOnRemoteAnswer)
            .putBoolean("ignore_ongoing", updated.ignoreOngoingNotifications)
            .apply()
    }

    fun saveManualTargetIp(context: Context, ip: String) {
        _connectionState.update { it.copy(manualTargetIp = ip.trim()) }
        context.getSharedPreferences("omnilink_prefs", Context.MODE_PRIVATE)
            .edit().putString("manual_target_ip", ip.trim()).apply()
    }

    fun updateConnection(transform: (LinkConnectionState) -> LinkConnectionState) {
        _connectionState.update(transform)
    }

    fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $message"
        _logs.update { current -> (listOf(line) + current).take(80) }
    }

    fun localDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    }

    @Synchronized
    private fun markMessageSeen(msgId: String): Boolean {
        if (seenMsgIds.contains(msgId)) return false
        seenMsgIds.add(msgId)
        if (seenMsgIds.size > 256) {
            val first = seenMsgIds.iterator().next()
            seenMsgIds.remove(first)
        }
        return true
    }

    fun sendEnvelope(envelope: BridgeEnvelope) {
        outboundSender?.invoke(envelope)
    }

    /**
     * Called when an Android notification is captured by NotificationListenerService.
     */
    fun onAndroidNotificationPosted(payload: NotificationPayload) {
        if (!_settings.value.forwardAndroidNotifications) return
        appendNotificationRecord(payload)
        sendEnvelope(
            BridgeEnvelope(
                type = MessageType.NOTIFICATION_POSTED,
                deviceName = localDeviceName(),
                platform = "android",
                notification = payload
            )
        )
    }

    fun onAndroidNotificationRemoved(key: String) {
        if (!_settings.value.forwardAndroidNotifications) return
        sendEnvelope(
            BridgeEnvelope(
                type = MessageType.NOTIFICATION_REMOVED,
                deviceName = localDeviceName(),
                platform = "android",
                removedKey = key
            )
        )
    }

    /**
     * Called when Android Telephony detects an incoming, answered, or ended cellular call.
     */
    fun onAndroidCallStateChanged(payload: CallStatePayload) {
        _localAndroidCall.value = if (payload.phase == CallPhase.ENDED) null else payload
        if (!_settings.value.forwardAndroidCalls) return
        log("安卓通话状态同步 -> iPhone: ${payload.phase} (${payload.callerName.ifBlank { payload.phoneNumber }})")
        sendEnvelope(
            BridgeEnvelope(
                type = MessageType.CALL_STATE,
                deviceName = localDeviceName(),
                platform = "android",
                callState = payload
            )
        )
    }

    /**
     * Called when ANCS (or custom BLE/LAN channel) receives a notification from iPhone.
     */
    fun onIphoneNotificationReceived(payload: NotificationPayload) {
        if (!_settings.value.receiveIphoneNotifications) return
        appendNotificationRecord(payload)
        onRemoteIphoneNotificationDisplay?.invoke(payload)
        log("收到 iPhone 通知 [${payload.appName}]: ${payload.title}")
    }

    /**
     * Called when ANCS detects an iPhone incoming call (CategoryID = 1) or call removal.
     */
    fun onIphoneCallStateChanged(payload: CallStatePayload?) {
        if (!_settings.value.receiveIphoneCalls && payload != null) return
        _remoteIphoneCall.value = payload
        onRemoteIphoneCallAlert?.invoke(payload)
        if (payload != null) {
            log("iPhone 来电提醒: ${payload.callerName.ifBlank { payload.phoneNumber }}")
        } else {
            log("iPhone 来电已结束或已处理")
        }
    }

    /**
     * Triggered when user taps "接听" or "挂断" on Android System Telecom UI / Notification / App UI for an iPhone call.
     */
    fun performIphoneCallAction(answer: Boolean, fromSystemTelecom: Boolean = false) {
        val call = _remoteIphoneCall.value ?: return
        log(if (answer) "发送指令: 接听 iPhone 来电" else "发送指令: 拒接/挂断 iPhone 来电")
        onRemoteIphoneCallAction?.invoke(call, answer)
        if (!fromSystemTelecom) {
            if (answer) {
                OmniTelecomConnectionService.markActiveFromAppUi()
            } else {
                OmniTelecomConnectionService.endConnectionRemotely(localReject = true)
            }
        }
        if (!answer) {
            onIphoneCallStateChanged(null)
        } else {
            val activeState = call.copy(phase = CallPhase.ACTIVE)
            _remoteIphoneCall.value = activeState
            onRemoteIphoneCallAlert?.invoke(activeState)
        }
    }

    /**
     * Handles incoming JSON envelopes from iPhone over BLE RX or LAN TCP.
     */
    fun handleIncomingEnvelope(envelope: BridgeEnvelope, channelLabel: String) {
        if (!markMessageSeen(envelope.msgId)) return

        when (envelope.type) {
            MessageType.HELLO -> {
                log("通过 $channelLabel 已握手设备: ${envelope.deviceName}")
            }
            MessageType.PING -> {
                sendEnvelope(
                    BridgeEnvelope(
                        type = MessageType.PONG,
                        deviceName = localDeviceName(),
                        platform = "android"
                    )
                )
            }
            MessageType.PONG -> Unit
            MessageType.NOTIFICATION_POSTED -> {
                envelope.notification?.let { onIphoneNotificationReceived(it) }
            }
            MessageType.NOTIFICATION_REMOVED -> Unit
            MessageType.CALL_STATE -> {
                val cs = envelope.callState ?: return
                if (cs.phase == CallPhase.ENDED) {
                    onIphoneCallStateChanged(null)
                } else {
                    val existing = _remoteIphoneCall.value
                    if (existing?.ancsUid != null && cs.ancsUid == null) {
                        // Prefer richer ANCS caller ID & action capability
                        return
                    }
                    onIphoneCallStateChanged(cs)
                }
            }
            MessageType.CALL_ACTION -> {
                val action = envelope.callAction ?: return
                log("收到 iPhone 远程电话控制指令: ${action.command}")
                onLocalAndroidCallCommand?.invoke(action.command)
            }
        }
    }

    fun clearNotifications() {
        _notifications.value = emptyList()
    }

    private fun appendNotificationRecord(item: NotificationPayload) {
        _notifications.update { list ->
            val deduplicated = list.filterNot {
                it.key == item.key && it.title == item.title && it.body == item.body
            }
            (listOf(item) + deduplicated).take(120)
        }
    }
}
