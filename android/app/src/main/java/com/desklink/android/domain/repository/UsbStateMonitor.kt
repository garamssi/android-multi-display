package com.desklink.android.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Emits whether the device currently has a USB data connection to a host.
 *
 * This is the only USB signal the app can observe without side effects: it reflects
 * the physical cable/data link, NOT whether the peer is specifically a Mac running
 * DeskLink (that is only known once the connect flow reaches the server). It is used
 * to drive the honest "USB connected / No USB" indicator on the Connection screen,
 * replacing the previously hardcoded "Mac detected".
 */
interface UsbStateMonitor {
    fun usbConnected(): Flow<Boolean>
}
