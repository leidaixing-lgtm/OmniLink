package com.omnilink.bridge.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.omnilink.bridge.ble.BleBridgeAndAncsManager
import com.omnilink.bridge.data.OmniRepository
import com.omnilink.bridge.lan.LanBridgeManager
import com.omnilink.bridge.protocol.BridgeEnvelope
import com.omnilink.bridge.protocol.CallActionPayload
import com.omnilink.bridge.protocol.MessageType
import com.omnilink.bridge.protocol.RemoteCallCommand
import com.omnilink.bridge.telephony.AndroidCallController
import com.omnilink.bridge.telephony.OmniTelecomConnectionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class OmniBridgeForegroundService : Service() {

    companion object {
        const val ACTION_RESTART_CHANNELS = "com.omnilink.bridge.RESTART_CHANNELS"
        const val ACTION_CONNECT_MANUAL_IP = "com.omnilink.bridge.CONNECT_MANUAL_IP"
        const val EXTRA_MANUAL_IP = "extra_manual_ip"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var notifier: RemoteAlertNotifier
    private lateinit var bleManager: BleBridgeAndAncsManager
    private lateinit var lanManager: LanBridgeManager
    private lateinit var callController: AndroidCallController

    override fun onCreate() {
        super.onCreate()
        OmniRepository.init(applicationContext)
        notifier = RemoteAlertNotifier(applicationContext)
        bleManager = BleBridgeAndAncsManager(applicationContext)
        lanManager = LanBridgeManager(applicationContext)
        callController = AndroidCallController(applicationContext)

        startForegroundCompat("正在初始化蓝牙 ANCS 与局域网互联通道...")

        // Wire outbound dual-channel sender (sends on LAN + BLE simultaneously; peer deduplicates by msgId)
        OmniRepository.outboundSender = { envelope ->
            lanManager.sendOverLan(envelope)
            bleManager.sendOverBle(envelope)
        }

        // Wire Android -> iPhone call answer/reject via ANCS Control Point (+ fallback JSON command)
        OmniRepository.onRemoteIphoneCallAction = { callState, answer ->
            val ancsUid = callState.ancsUid
            if (ancsUid != null) {
                bleManager.performAncsCallAction(ancsUid, positive = answer)
            }
            val fallbackCmd = if (answer) RemoteCallCommand.ANSWER else RemoteCallCommand.REJECT
            OmniRepository.sendEnvelope(
                BridgeEnvelope(
                    type = MessageType.CALL_ACTION,
                    deviceName = OmniRepository.localDeviceName(),
                    platform = "android",
                    callAction = CallActionPayload(callId = callState.callId, command = fallbackCmd)
                )
            )
        }

        // Wire iPhone -> Android local cellular call execution
        OmniRepository.onLocalAndroidCallCommand = { cmd ->
            callController.executeRemoteCommand(cmd)
        }

        // Wire UI / System alerts for incoming iPhone notifications & calls
        OmniRepository.onRemoteIphoneNotificationDisplay = { payload ->
            notifier.showMirroredIphoneNotification(payload)
        }
        OmniRepository.onRemoteIphoneCallAlert = { call ->
            notifier.updateIphoneCallAlert(call)
        }

        startAllTransports()

        serviceScope.launch {
            OmniRepository.connectionState.collectLatest { state ->
                val summary = buildString {
                    if (state.ancsSubscribed) append("ANCS 已连通 · ")
                    else if (state.bleConnectedPeer != null) append("BLE 已连接 ${state.bleConnectedPeer} · ")
                    else if (state.bleAdvertising) append("BLE 待配对 · ")

                    if (state.lanConnectedPeer != null) append("Wi-Fi 已连 ${state.lanConnectedPeer}")
                    else append("本机 IP: ${state.lanLocalIp}")
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(
                    RemoteAlertNotifier.FOREGROUND_NOTIF_ID,
                    notifier.buildForegroundNotification(summary)
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RESTART_CHANNELS -> {
                bleManager.stop()
                lanManager.stop()
                callController.stop()
                startAllTransports()
            }
            ACTION_CONNECT_MANUAL_IP -> {
                val ip = intent.getStringExtra(EXTRA_MANUAL_IP).orEmpty()
                if (ip.isNotBlank()) {
                    lanManager.connectToPeerIp(ip)
                }
            }
        }
        return START_STICKY
    }

    private fun startAllTransports() {
        OmniRepository.updateConnection { it.copy(isServiceRunning = true) }
        OmniTelecomConnectionService.registerPhoneAccounts(applicationContext)
        try {
            bleManager.start()
        } catch (e: Exception) {
            OmniRepository.log("BLE 启动受限 (请确认已授蓝牙权限): ${e.message}")
        }
        lanManager.start()
        callController.start()
    }

    private fun startForegroundCompat(summary: String) {
        val notification = notifier.buildForegroundNotification(summary)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    RemoteAlertNotifier.FOREGROUND_NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(RemoteAlertNotifier.FOREGROUND_NOTIF_ID, notification)
            }
        } catch (_: Exception) {
            // In case Android 14 BLUETOOTH_CONNECT permission isn't granted on first launch yet,
            // fall back gracefully until user taps Grant Permissions on MainActivity
        }
    }

    override fun onDestroy() {
        OmniRepository.updateConnection { it.copy(isServiceRunning = false) }
        bleManager.stop()
        lanManager.stop()
        callController.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
