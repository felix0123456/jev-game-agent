package com.jev.gameagent.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlin.random.Random

class AccessibilityTouchService : AccessibilityService(), InputDispatcher {

    companion object {
        private const val TAG = "AccessibilityTouch"
        var instance: AccessibilityTouchService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityTouchService connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can be used to track window state changes or foreground package switches
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityTouchService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.i(TAG, "AccessibilityTouchService destroyed")
    }

    override fun isReady(): Boolean = instance != null

    @RequiresApi(Build.VERSION_CODES.N)
    override fun tap(x: Float, y: Float, durationMs: Long, callback: ((Boolean) -> Unit)?) {
        val path = Path().apply {
            moveTo(x, y)
        }
        // Add subtle human-like touch jitter (+/- 2 pixels)
        val jitterX = x + (Random.nextFloat() * 4f - 2f)
        val jitterY = y + (Random.nextFloat() * 4f - 2f)
        path.lineTo(jitterX, jitterY)

        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(40))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap gesture cancelled at ($x, $y)")
                callback?.invoke(false)
            }
        }, null)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        callback: ((Boolean) -> Unit)?
    ) {
        val path = Path().apply {
            moveTo(startX, startY)
            // Add a gentle curved arc to imitate human thumb motion
            val midX = (startX + endX) / 2f + (Random.nextFloat() * 10f - 5f)
            val midY = (startY + endY) / 2f + (Random.nextFloat() * 10f - 5f)
            quadTo(midX, midY, endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(100))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Swipe gesture cancelled")
                callback?.invoke(false)
            }
        }, null)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun swipeDirection(
        direction: SwipeDirection,
        screenWidth: Int,
        screenHeight: Int,
        durationMs: Long,
        callback: ((Boolean) -> Unit)?
    ) {
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val distanceX = screenWidth * 0.35f
        val distanceY = screenHeight * 0.25f

        val (startX, startY, endX, endY) = when (direction) {
            SwipeDirection.UP -> listOf(centerX, centerY + distanceY, centerX, centerY - distanceY)
            SwipeDirection.DOWN -> listOf(centerX, centerY - distanceY, centerX, centerY + distanceY)
            SwipeDirection.LEFT -> listOf(centerX + distanceX, centerY, centerX - distanceX, centerY)
            SwipeDirection.RIGHT -> listOf(centerX - distanceX, centerY, centerX + distanceX, centerY)
        }

        swipe(startX, startY, endX, endY, durationMs, callback)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun hold(x: Float, y: Float, durationMs: Long, callback: ((Boolean) -> Unit)?) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun dragPath(points: List<PointF>, durationMs: Long, callback: ((Boolean) -> Unit)?) {
        if (points.isEmpty()) {
            callback?.invoke(false)
            return
        }

        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) {
                lineTo(points[i].x, points[i].y)
            }
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }
}
