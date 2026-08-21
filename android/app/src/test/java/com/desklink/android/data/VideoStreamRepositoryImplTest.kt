package com.desklink.android.data

import android.view.Surface
import com.desklink.android.data.codec.HEVCDecoder
import com.desklink.android.data.network.TCPClient
import com.desklink.android.data.video.VideoStreamRepositoryImpl
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.domain.transport.Transport
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// While backgrounded (no output Surface) the decoder must not render: releasing buffers to a destroyed Surface faults MediaCodec (black screen on resume).
class VideoStreamRepositoryImplTest {

    private val transport = object : Transport {
        override suspend fun host() = "127.0.0.1"
        override fun controlPort() = ProtocolConstants.PORT_CONTROL
        override fun videoPort() = ProtocolConstants.PORT_VIDEO
        override fun inputPort() = ProtocolConstants.PORT_INPUT
        override fun audioPort() = ProtocolConstants.PORT_AUDIO
    }

    private val VSYNC_NANOS = 1_234_567_890L
    private val VSYNC_PERIOD_NANOS = 16_666_667L

    @Test
    fun `renderFrame is skipped while there is no surface`() {
        val decoder = mockk<HEVCDecoder>(relaxed = true)
        val repo = VideoStreamRepositoryImpl(mockk<TCPClient>(relaxed = true), decoder, transport)

        assertFalse(repo.renderFrame(VSYNC_NANOS, VSYNC_PERIOD_NANOS))
        verify(exactly = 0) { decoder.renderFrame(any(), any()) }
    }

    @Test
    fun `renderFrame drives the decoder once a surface is present`() {
        val decoder = mockk<HEVCDecoder>(relaxed = true)
        every { decoder.renderFrame(any(), any()) } returns true
        val repo = VideoStreamRepositoryImpl(mockk<TCPClient>(relaxed = true), decoder, transport)

        repo.setSurface(mockk<Surface>(relaxed = true))

        assertTrue(repo.renderFrame(VSYNC_NANOS, VSYNC_PERIOD_NANOS))
        // The vsync time must reach the decoder: it is the lip-sync reference clock.
        verify(exactly = 1) { decoder.renderFrame(VSYNC_NANOS, VSYNC_PERIOD_NANOS) }
    }
}
