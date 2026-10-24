package com.jev.gameagent.input

import android.graphics.PointF

enum class SwipeDirection {
    UP, DOWN, LEFT, RIGHT
}

interface InputDispatcher {
    /**
     * Dispatches a single tap at physical screen coordinates (x, y).
     */
    fun tap(x: Float, y: Float, durationMs: Long = 60, callback: ((Boolean) -> Unit)? = null)

    /**
     * Dispatches a swipe gesture between two points.
     */
    fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 250,
        callback: ((Boolean) -> Unit)? = null
    )

    /**
     * Dispatches a directional swipe based on device screen dimensions.
     */
    fun swipeDirection(
        direction: SwipeDirection,
        screenWidth: Int,
        screenHeight: Int,
        durationMs: Long = 280,
        callback: ((Boolean) -> Unit)? = null
    )

    /**
     * Dispatches a directional swipe localized inside a specific screen rectangle or list container.
     */
    fun swipeArea(
        direction: SwipeDirection,
        area: com.jev.gameagent.jev.ScreenRect,
        durationMs: Long = 280,
        callback: ((Boolean) -> Unit)? = null
    )

    /**
     * Dispatches a continuous touch / hold gesture.
     */
    fun hold(x: Float, y: Float, durationMs: Long, callback: ((Boolean) -> Unit)? = null)

    /**
     * Dispatches a multi-point continuous path (e.g. curve or drag).
     */
    fun dragPath(points: List<PointF>, durationMs: Long, callback: ((Boolean) -> Unit)? = null)

    /**
     * Returns true if the service is currently bound and ready to inject gestures.
     */
    fun isReady(): Boolean
}
