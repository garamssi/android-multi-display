package com.desklink.android.di

import com.desklink.android.data.device.AndroidScreenMetricsProvider
import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.data.device.UsbStateMonitorImpl
import com.desklink.android.data.input.InputRepositoryImpl
import com.desklink.android.data.network.ConnectionManagerImpl
import com.desklink.android.data.transport.UsbTransport
import com.desklink.android.data.video.VideoStreamRepositoryImpl
import com.desklink.android.domain.repository.ConnectionRepository
import com.desklink.android.domain.repository.InputRepository
import com.desklink.android.domain.repository.UsbStateMonitor
import com.desklink.android.domain.repository.VideoStreamRepository
import com.desklink.android.domain.transport.Transport
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings mapping repository implementations to their domain interfaces
 * (A-C4). Concrete impls are constructor-injected (@Inject), so @Binds is enough.
 * Each impl owns its own [com.desklink.android.data.network.TCPClient] for its
 * dedicated channel (control / video / input).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindConnectionRepository(
        impl: ConnectionManagerImpl,
    ): ConnectionRepository

    @Binds
    @Singleton
    abstract fun bindInputRepository(
        impl: InputRepositoryImpl,
    ): InputRepository

    @Binds
    @Singleton
    abstract fun bindVideoStreamRepository(
        impl: VideoStreamRepositoryImpl,
    ): VideoStreamRepository

    @Binds
    @Singleton
    abstract fun bindScreenMetricsProvider(
        impl: AndroidScreenMetricsProvider,
    ): ScreenMetricsProvider

    @Binds
    @Singleton
    abstract fun bindUsbStateMonitor(
        impl: UsbStateMonitorImpl,
    ): UsbStateMonitor

    // USB is the only transport today; the LAN transport binds here in a later phase.
    @Binds
    @Singleton
    abstract fun bindTransport(
        impl: UsbTransport,
    ): Transport
}
