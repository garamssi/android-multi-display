package com.desklink.android.data

import com.desklink.android.data.device.ScreenMetricsProvider
import com.desklink.android.data.device.ScreenResolution
import com.desklink.android.data.settings.SettingsRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// A stored resolution outlives the panel it was chosen on: the same app data can arrive on a
// different tablet through a backup restore, and a preset picked on a 3200x2000 panel is
// larger than a 1920x1200 one can show.
class StoredResolutionTest {

    private fun metrics(width: Int, height: Int) = object : ScreenMetricsProvider {
        override fun nativeResolution() = ScreenResolution(width, height)
        override fun maxRefreshRate() = 60
    }

    @Test
    fun `a stored resolution larger than the panel is clamped to it`() {
        val store = FakeSettingsStore()
        store.putInt("width", 2400)
        store.putInt("height", 1500)

        val repo = SettingsRepository(metrics(1920, 1200), store)

        assertTrue(
            repo.config.value.width <= 1920 && repo.config.value.height <= 1200,
            "streaming ${repo.config.value.width}x${repo.config.value.height} to a 1920x1200 panel",
        )
    }

    @Test
    fun `a stored resolution the panel can show is kept`() {
        val store = FakeSettingsStore()
        store.putInt("width", 1600)
        store.putInt("height", 1000)

        val repo = SettingsRepository(metrics(3200, 2000), store)

        assertEquals(1600, repo.config.value.width)
        assertEquals(1000, repo.config.value.height)
    }

    @Test
    fun `no stored resolution means the panel's own size`() {
        val repo = SettingsRepository(metrics(2560, 1600), FakeSettingsStore())

        assertEquals(2560, repo.config.value.width)
        assertEquals(1600, repo.config.value.height)
    }
}
