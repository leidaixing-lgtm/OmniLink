package com.omnilink.bridge

import android.app.Application
import com.omnilink.bridge.data.OmniRepository

class OmniLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OmniRepository.init(this)
    }
}
