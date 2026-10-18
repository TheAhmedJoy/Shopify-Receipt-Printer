package com.example.shopify_receipt_printer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the print service after a reboot, power cut or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> if (Settings(context).serviceEnabled) PrintService.start(context)
        }
    }
}
