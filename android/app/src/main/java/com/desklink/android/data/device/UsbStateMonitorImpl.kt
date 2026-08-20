package com.desklink.android.data.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.desklink.android.domain.repository.UsbStateMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbStateMonitorImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : UsbStateMonitor {

    override fun usbConnected(): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(intent?.isUsbConnected() ?: false)
            }
        }
        val filter = IntentFilter(ACTION_USB_STATE)
        // ACTION_USB_STATE is sticky: registering returns the current state immediately.
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        trySend(sticky?.isUsbConnected() ?: false)

        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun Intent.isUsbConnected(): Boolean = getBooleanExtra(EXTRA_CONNECTED, false)

    private companion object {
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        const val EXTRA_CONNECTED = "connected"
    }
}
