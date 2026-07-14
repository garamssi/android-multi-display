package com.desklink.android.domain.transport

interface Transport {
    suspend fun host(): String

    fun controlPort(): Int

    fun videoPort(): Int

    fun inputPort(): Int
}
