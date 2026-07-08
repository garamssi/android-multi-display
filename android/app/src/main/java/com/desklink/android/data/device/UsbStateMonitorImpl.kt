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

/**
 * Observes the device's USB gadget state via the `ACTION_USB_STATE` system broadcast.
 *
 * `ACTION_USB_STATE` is a *sticky* broadcast, so registering the receiver delivers the
 * current state immediately (no wait for a plug/unplug event); the `connected` extra
 * is true when a USB data link to a host exists. The action/extra are de-facto system
 * strings (not public SDK constants) but have been stable across Android versions; if
 * a device never delivers them, the flow simply stays `false` — which honestly reads
 * as "No USB" rather than a false-positive.
 */
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
        // NOT_EXPORTED: only the system delivers this protected broadcast to us. The
        // returned sticky intent gives the current state right away.
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
