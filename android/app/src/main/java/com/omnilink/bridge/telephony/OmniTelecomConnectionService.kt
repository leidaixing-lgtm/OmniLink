package com.omnilink.bridge.telephony

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.omnilink.bridge.data.OmniRepository
import com.omnilink.bridge.protocol.CallStatePayload

/**
 * Integrates incoming iPhone calls directly into the Android System Telecom Framework:
 * 1. Managed Mode (`CAPABILITY_CALL_PROVIDER`): Invokes the native Android System Phone App (`InCallUI`)!
 * 2. Self-Managed Mode (`CAPABILITY_SELF_MANAGED`): Coordinates Bluetooth HFP buttons, Wear OS watches,
 *    and pairs with Android System `NotificationCompat.CallStyle` when Managed account is not toggled on yet.
 */
class OmniTelecomConnectionService : ConnectionService() {

    companion object {
        private const val ACCOUNT_ID_SYSTEM_INCALL = "omnilink_system_incall"
        private const val ACCOUNT_ID_SELF_MANAGED = "omnilink_self_managed"

        const val EXTRA_CALLER_NAME = "extra_omnilink_caller_name"
        const val EXTRA_PHONE_NUMBER = "extra_omnilink_phone_number"
        const val EXTRA_CALL_ID = "extra_omnilink_call_id"
        const val EXTRA_IS_MANAGED_UI = "extra_omnilink_is_managed_ui"

        @Volatile
        var activeConnection: OmniIphoneConnection? = null
            private set

        fun systemInCallHandle(context: Context): PhoneAccountHandle {
            return PhoneAccountHandle(
                ComponentName(context, OmniTelecomConnectionService::class.java),
                ACCOUNT_ID_SYSTEM_INCALL
            )
        }

        fun selfManagedHandle(context: Context): PhoneAccountHandle {
            return PhoneAccountHandle(
                ComponentName(context, OmniTelecomConnectionService::class.java),
                ACCOUNT_ID_SELF_MANAGED
            )
        }

        fun registerPhoneAccounts(context: Context) {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return

            // 1. Register Managed Call Provider Account (uses Android System Built-in Phone / InCallUI)
            try {
                val sysHandle = systemInCallHandle(context)
                val sysAccount = PhoneAccount.builder(sysHandle, "双机互联 (iPhone 来电接听)")
                    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .setShortDescription("使用安卓系统原生电话界面接听或挂断 iPhone 来电")
                    .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                    .build()
                tm.registerPhoneAccount(sysAccount)
            } catch (e: Exception) {
                OmniRepository.log("系统电话插件账户注册提示: ${e.message}")
            }

            // 2. Register Self-Managed Account (zero-config fallback for Telecom + Bluetooth headset buttons)
            try {
                val smHandle = selfManagedHandle(context)
                val smAccount = PhoneAccount.builder(smHandle, "双机互联通话服务")
                    .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                    .build()
                tm.registerPhoneAccount(smAccount)
            } catch (_: Exception) {
            }
        }

        @SuppressLint("MissingPermission")
        fun isSystemInCallAccountEnabled(context: Context): Boolean {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
            return try {
                tm.getPhoneAccount(systemInCallHandle(context))?.isEnabled == true
            } catch (_: Exception) {
                false
            }
        }

        fun buildEnableCallingAccountsIntent(): Intent {
            return Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        /**
         * Reports an incoming iPhone call to Android Telecom.
         * @return `true` if handed off to Android's native System Phone `InCallUI` (so no custom notification UI is needed),
         *         `false` if using Self-Managed / CallStyle system call card.
         */
        @SuppressLint("MissingPermission")
        fun reportIncomingIphoneCall(context: Context, call: CallStatePayload): Boolean {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return false
            registerPhoneAccounts(context)

            val sanitizedNumber = call.phoneNumber
                .filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                .ifBlank { "10000" }
            val addressUri = Uri.fromParts(PhoneAccount.SCHEME_TEL, sanitizedNumber, null)

            // Try 1: Native Android System Phone App (InCallUI) if enabled by user
            if (isSystemInCallAccountEnabled(context)) {
                try {
                    val extras = Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, addressUri)
                        putString(EXTRA_CALLER_NAME, call.callerName)
                        putString(EXTRA_PHONE_NUMBER, call.phoneNumber)
                        putString(EXTRA_CALL_ID, call.callId)
                        putBoolean(EXTRA_IS_MANAGED_UI, true)
                    }
                    tm.addNewIncomingCall(systemInCallHandle(context), extras)
                    OmniRepository.log("已调起安卓系统原生电话界面 (System InCallUI)")
                    return true
                } catch (e: Exception) {
                    OmniRepository.log("系统 InCallUI 调用受限 (${e.message})，切换至系统 CallStyle 通话插件")
                }
            }

            // Try 2: Self-Managed Telecom Connection (enables Bluetooth headset & watch call controls alongside CallStyle)
            try {
                val extras = Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, addressUri)
                    putString(EXTRA_CALLER_NAME, call.callerName)
                    putString(EXTRA_PHONE_NUMBER, call.phoneNumber)
                    putString(EXTRA_CALL_ID, call.callId)
                    putBoolean(EXTRA_IS_MANAGED_UI, false)
                }
                tm.addNewIncomingCall(selfManagedHandle(context), extras)
            } catch (_: Exception) {
            }
            return false
        }

        fun markActiveFromAppUi() {
            activeConnection?.setActive()
        }

        fun endConnectionRemotely(localReject: Boolean = false) {
            val conn = activeConnection ?: return
            activeConnection = null
            val cause = if (localReject) DisconnectCause.LOCAL else DisconnectCause.REMOTE
            conn.setDisconnected(DisconnectCause(cause))
            conn.destroy()
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        activeConnection?.let { old ->
            old.setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
            old.destroy()
        }

        val extras = request?.extras ?: Bundle()
        val callerName = extras.getString(EXTRA_CALLER_NAME).orEmpty().ifBlank { "iPhone 来电" }
        val rawPhone = extras.getString(EXTRA_PHONE_NUMBER).orEmpty()
        val isManagedUi = extras.getBoolean(EXTRA_IS_MANAGED_UI, false)

        val conn = OmniIphoneConnection(isManagedUi = isManagedUi).apply {
            val cleanDigits = rawPhone.filter { it.isDigit() || it == '+' }.ifBlank { "10000" }
            setAddress(
                Uri.fromParts(PhoneAccount.SCHEME_TEL, cleanDigits, null),
                TelecomManager.PRESENTATION_ALLOWED
            )
            setCallerDisplayName(
                "[iPhone] $callerName",
                TelecomManager.PRESENTATION_ALLOWED
            )
            if (!isManagedUi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                connectionProperties = Connection.PROPERTY_SELF_MANAGED
            }
            audioModeIsVoip = true
            setInitializing()
            setRinging()
        }

        activeConnection = conn
        return conn
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        OmniRepository.log("安卓 Telecom 来电连接创建被系统拒绝，使用系统 CallStyle 卡片提醒")
    }

    class OmniIphoneConnection(val isManagedUi: Boolean) : Connection() {

        override fun onShowIncomingCallUi() {
            // Only called for CAPABILITY_SELF_MANAGED; RemoteAlertNotifier posts NotificationCompat.CallStyle
        }

        override fun onAnswer() {
            setActive()
            OmniRepository.performIphoneCallAction(answer = true, fromSystemTelecom = true)
        }

        override fun onAnswer(videoState: Int) {
            setActive()
            OmniRepository.performIphoneCallAction(answer = true, fromSystemTelecom = true)
        }

        override fun onReject() {
            activeConnection = null
            setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
            destroy()
            OmniRepository.performIphoneCallAction(answer = false, fromSystemTelecom = true)
        }

        override fun onDisconnect() {
            activeConnection = null
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
            OmniRepository.performIphoneCallAction(answer = false, fromSystemTelecom = true)
        }
    }
}
