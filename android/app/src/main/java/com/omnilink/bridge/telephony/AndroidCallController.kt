package com.omnilink.bridge.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.omnilink.bridge.data.OmniRepository
import com.omnilink.bridge.protocol.CallPhase
import com.omnilink.bridge.protocol.CallStatePayload
import com.omnilink.bridge.protocol.RemoteCallCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AndroidCallController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val telecomManager =
        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var activeCallId: String = "android-call-0"
    private var lastKnownNumber: String = ""
    private var lastKnownName: String = ""
    private var registered = false

    private var api31Callback: Any? = null

    @Suppress("DEPRECATION")
    private val legacyListener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (!phoneNumber.isNullOrBlank()) {
                updateIncomingCallerInfo(phoneNumber)
            }
            handleAndroidCallState(state)
        }
    }

    companion object {
        @Volatile
        var latestScreenedNumber: String = ""
    }

    fun start() {
        if (registered) return
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val tm = telephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerModernCallback(tm)
            } else {
                @Suppress("DEPRECATION")
                tm.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
            registered = true
            OmniRepository.log("安卓本机来电监听与接听控制已就绪")
        } catch (e: Exception) {
            OmniRepository.log("来电监听注册失败: ${e.message}")
        }
    }

    fun stop() {
        if (!registered) return
        val tm = telephonyManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                unregisterModernCallback(tm)
            } else {
                @Suppress("DEPRECATION")
                tm.listen(legacyListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) {
        }
        registered = false
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerModernCallback(tm: TelephonyManager) {
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleAndroidCallState(state)
            }
        }
        api31Callback = cb
        tm.registerTelephonyCallback(context.mainExecutor, cb)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregisterModernCallback(tm: TelephonyManager) {
        val cb = api31Callback as? TelephonyCallback ?: return
        tm.unregisterTelephonyCallback(cb)
        api31Callback = null
    }

    private fun updateIncomingCallerInfo(rawNumber: String) {
        lastKnownNumber = rawNumber
        lastKnownName = lookupContactName(rawNumber)
    }

    private fun handleAndroidCallState(state: Int) {
        // Prevent echoing our own virtual OmniTelecomConnectionService (for incoming iPhone calls) back to iPhone!
        if (OmniTelecomConnectionService.activeConnection != null ||
            OmniRepository.remoteIphoneCall.value != null
        ) {
            return
        }

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                activeCallId = "android-call-${System.currentTimeMillis()}"
                scope.launch {
                    // Brief wait for CallScreeningService or CallLog if number wasn't immediately populated
                    if (latestScreenedNumber.isNotBlank()) {
                        updateIncomingCallerInfo(latestScreenedNumber)
                    } else if (lastKnownNumber.isBlank()) {
                        delay(250)
                        if (latestScreenedNumber.isNotBlank()) {
                            updateIncomingCallerInfo(latestScreenedNumber)
                        }
                    }

                    val displayNumber = lastKnownNumber.ifBlank { "安卓 SIM 卡来电" }
                    val displayName = lastKnownName.ifBlank { displayNumber }
                    OmniRepository.onAndroidCallStateChanged(
                        CallStatePayload(
                            callId = activeCallId,
                            phase = CallPhase.RINGING,
                            phoneNumber = displayNumber,
                            callerName = displayName,
                            sourcePlatform = "android",
                            canAnswer = true,
                            canReject = true
                        )
                    )
                }
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val displayNumber = lastKnownNumber.ifBlank { "通话中" }
                val displayName = lastKnownName.ifBlank { displayNumber }
                OmniRepository.onAndroidCallStateChanged(
                    CallStatePayload(
                        callId = activeCallId,
                        phase = CallPhase.ACTIVE,
                        phoneNumber = displayNumber,
                        callerName = displayName,
                        sourcePlatform = "android"
                    )
                )
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                OmniRepository.onAndroidCallStateChanged(
                    CallStatePayload(
                        callId = activeCallId,
                        phase = CallPhase.ENDED,
                        phoneNumber = lastKnownNumber,
                        callerName = lastKnownName,
                        sourcePlatform = "android"
                    )
                )
                lastKnownNumber = ""
                lastKnownName = ""
                latestScreenedNumber = ""
            }
        }
    }

    /**
     * Executes remote call control commands received from the user's iPhone (CallKit / App UI).
     */
    @SuppressLint("MissingPermission")
    fun executeRemoteCommand(command: RemoteCallCommand) {
        when (command) {
            RemoteCallCommand.ANSWER -> {
                if (hasAnswerCallPermission()) {
                    try {
                        @Suppress("DEPRECATION")
                        telecomManager?.acceptRingingCall()
                        OmniRepository.log("已通过 TelecomManager 自动接听安卓来电")
                        if (OmniRepository.settings.value.autoSpeakerOnRemoteAnswer) {
                            scope.launch {
                                delay(500)
                                setSpeakerphone(true)
                            }
                        }
                    } catch (e: Exception) {
                        OmniRepository.log("接听安卓来电失败: ${e.message}")
                    }
                } else {
                    OmniRepository.log("缺少「接听电话 (ANSWER_PHONE_CALLS)」权限，请在首页授权")
                }
            }

            RemoteCallCommand.REJECT,
            RemoteCallCommand.HANGUP -> {
                if (hasAnswerCallPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        @Suppress("DEPRECATION")
                        telecomManager?.endCall()
                        OmniRepository.log("已远程挂断安卓通话")
                    } catch (e: Exception) {
                        OmniRepository.log("挂断通话失败: ${e.message}")
                    }
                } else {
                    OmniRepository.log("缺少挂断电话权限")
                }
            }

            RemoteCallCommand.SPEAKER_ON -> setSpeakerphone(true)
            RemoteCallCommand.SPEAKER_OFF -> setSpeakerphone(false)
        }
    }

    private fun setSpeakerphone(enabled: Boolean) {
        try {
            @Suppress("DEPRECATION")
            audioManager?.isSpeakerphoneOn = enabled
            OmniRepository.log(if (enabled) "已开启安卓扬声器免提" else "已关闭安卓扬声器免提")
        } catch (_: Exception) {
        }
    }

    private fun hasAnswerCallPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun lookupContactName(phoneNumber: String): String {
        if (phoneNumber.isBlank()) return ""
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return ""
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
            }.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
