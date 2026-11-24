package com.omnilink.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, OmniBridgeForegroundService::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }
}
