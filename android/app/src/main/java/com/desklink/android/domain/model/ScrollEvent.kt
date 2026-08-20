package com.desklink.android.domain.model

data class ScrollEvent(val deltaX: Float, val deltaY: Float) {
    companion object {
        const val SERIALIZED_SIZE = 8
    }
}
