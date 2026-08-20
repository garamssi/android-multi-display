package com.desklink.android.domain.repository

import kotlinx.coroutines.flow.Flow

interface UsbStateMonitor {
    fun usbConnected(): Flow<Boolean>
}
