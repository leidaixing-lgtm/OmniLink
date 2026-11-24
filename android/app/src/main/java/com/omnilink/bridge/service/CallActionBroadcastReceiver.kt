package com.omnilink.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omnilink.bridge.data.OmniRepository

class CallActionBroadcastReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ANSWER_IPHONE_CALL = "com.omnilink.bridge.ACTION_ANSWER_IPHONE_CALL"
        const val ACTION_REJECT_IPHONE_CALL = "com.omnilink.bridge.ACTION_REJECT_IPHONE_CALL"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_ANSWER_IPHONE_CALL -> OmniRepository.performIphoneCallAction(answer = true)
            ACTION_REJECT_IPHONE_CALL -> OmniRepository.performIphoneCallAction(answer = false)
        }
    }
}
