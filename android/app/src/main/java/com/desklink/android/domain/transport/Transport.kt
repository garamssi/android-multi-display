package com.desklink.android.domain.transport

interface Transport {
    suspend fun host(): String

    fun controlPort(): Int

    fun videoPort(): Int

    fun inputPort(): Int

    // Null when this transport does not carry audio, so the caller can skip the channel
    // instead of connecting to a port nothing serves.
    fun audioPort(): Int?
}
