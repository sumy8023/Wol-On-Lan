package com.example.wolquicktile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AndroidProxyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AndroidProxyServiceController.isEnabled(context)) return
        val pending = goAsync()
        try {
            AndroidProxyServiceController.start(context)
        } finally {
            pending.finish()
        }
    }
}
