package com.desklink.android.data.settings

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
