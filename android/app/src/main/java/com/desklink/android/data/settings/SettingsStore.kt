package com.desklink.android.data.settings

/**
 * Small typed key/value persistence for user settings, so a choice (transport mode,
 * manual host, resolution, scroll preferences) survives app restarts.
 *
 * Abstracted behind an interface so [SettingsRepository] stays unit-testable with an
 * in-memory fake — no Android Context in JVM tests, and the synchronous reads keep the
 * repository's eager (construction-time) seeding race-free.
 */
interface SettingsStore {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getFloat(key: String, default: Float): Float
    fun putFloat(key: String, value: Float)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
}
