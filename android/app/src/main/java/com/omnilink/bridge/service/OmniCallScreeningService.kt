package com.omnilink.bridge.service

import android.telecom.Call
import android.telecom.CallScreeningService
import com.omnilink.bridge.telephony.AndroidCallController

class OmniCallScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        if (number.isNotBlank()) {
            AndroidCallController.latestScreenedNumber = number
        }
        // Always allow the call through untouched
        respondToCall(callDetails, CallResponse.Builder().build())
    }
}
