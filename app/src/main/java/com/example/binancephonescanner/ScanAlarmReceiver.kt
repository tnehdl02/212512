package com.example.binancephonescanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ScanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ScannerService::class.java).setAction(ScannerService.ACTION_SCAN)
        )
    }
}
