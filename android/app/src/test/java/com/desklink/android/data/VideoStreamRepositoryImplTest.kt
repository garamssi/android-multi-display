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
    }

    @Test
    fun `renderFrame is skipped while there is no surface`() {
        val decoder = mockk<HEVCDecoder>(relaxed = true)
        val repo = VideoStreamRepositoryImpl(mockk<TCPClient>(relaxed = true), decoder, transport)

        assertFalse(repo.renderFrame())
        verify(exactly = 0) { decoder.renderFrame() }
    }

    @Test
    fun `renderFrame drives the decoder once a surface is present`() {
        val decoder = mockk<HEVCDecoder>(relaxed = true)
        every { decoder.renderFrame() } returns true
        val repo = VideoStreamRepositoryImpl(mockk<TCPClient>(relaxed = true), decoder, transport)

        repo.setSurface(mockk<Surface>(relaxed = true))

        assertTrue(repo.renderFrame())
        verify(exactly = 1) { decoder.renderFrame() }
    }
}
