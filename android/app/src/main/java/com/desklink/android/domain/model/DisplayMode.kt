package com.desklink.android.domain.model

// How the tablet's screen relates to the Mac's. Decided by the Mac and announced in the
// handshake: extend adds a display to the Mac's arrangement, and whether input is accepted
// must be the server's call rather than something the client asserts.
enum class DisplayMode(val wireValue: String) {
    // A separate display; the tablet shows content the Mac's own screen does not.
    EXTEND("extend"),

    // The Mac's own screen. Touch is refused because it would move the cursor for whoever
    // is using the Mac; the server does not even bind the input port.
    MIRROR("mirror");

    val acceptsInput: Boolean get() = this == EXTEND

    companion object {
        fun fromWire(value: String?): DisplayMode? =
            entries.firstOrNull { it.wireValue == value }

        // An older server sends no mode. Extend keeps that case behaving exactly as before
        // instead of silently disabling touch.
        val DEFAULT = EXTEND
    }
}
