package com.jev.gameagent.overlay

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.jev.gameagent.JevApplication
import com.jev.gameagent.core.AgentCoordinator
import com.jev.gameagent.core.AgentState
import com.jev.gameagent.core.AgentStateListener
import com.jev.gameagent.jev.ScreenRect

class FloatingOverlayService : Service(), AgentStateListener {

    companion object {
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var hudOverlayView: View? = null
    private var activeRect: ScreenRect? = null
    private var activeConfidence: Float = 0f
    private var activeLatencyMs: Long = 0L

    private val coordinator: AgentCoordinator
        get() = JevApplication.instance.coordinator

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        coordinator.setListener(this)

        startForegroundNotification()
        createBubbleView()
        createHudOverlayView()
    }

    private fun startForegroundNotification() {
        val notification: Notification = NotificationCompat.Builder(
            this,
            JevApplication.CHANNEL_ID_OVERLAY
        )
            .setContentTitle("Jev Game Autopilot")
            .setContentText("Floating controls active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createBubbleView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#E6212121"))
        }

        val icon = TextView(this).apply {
            text = "⚡ JEV"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
        }

        val stopBtn = Button(this).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D50000"))
            textSize = 11f
            setOnClickListener {
                coordinator.stopObjective("User pressed floating STOP")
            }
        }

        val startBtn = Button(this).apply {
            text = "GOAL"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2979FF"))
            textSize = 11f
            setOnClickListener {
                showObjectiveInputDialog()
            }
        }

        container.addView(icon)
        container.addView(startBtn)
        container.addView(stopBtn)

        // Make draggable
        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(container, params)
                        return true
                    }
                }
                return false
            }
        })

        bubbleView = container
        windowManager.addView(container, params)
    }

    private fun createHudOverlayView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val hudView = object : View(this) {
            private val boxPaint = Paint().apply {
                color = Color.parseColor("#00E676")
                style = Paint.Style.STROKE
                strokeWidth = 6f
                isAntiAlias = true
            }

            private val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 34f
                setShadowLayer(4f, 2f, 2f, Color.BLACK)
                isAntiAlias = true
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                activeRect?.let { rect ->
                    canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, boxPaint)
                    val label = "Jev: %.0f%% (%d ms)".format(activeConfidence * 100f, activeLatencyMs)
                    canvas.drawText(label, rect.left, (rect.top - 12f).coerceAtLeast(40f), textPaint)
                }
            }
        }

        hudOverlayView = hudView
        windowManager.addView(hudView, params)
    }

    private fun showObjectiveInputDialog() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.6f
            gravity = Gravity.CENTER
        }

        val dialogContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        val title = TextView(this).apply {
            text = "Enter Game Objective"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }

        val input = EditText(this).apply {
            hint = "e.g., finish this level in Subway Surfers"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(coordinator.activeObjective.ifEmpty { "finish this level" })
        }

        val presetGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val presets = listOf(
            "finish this level",
            "finish this mission",
            "defeat current boss",
            "claim daily quest rewards"
        )

        for (preset in presets) {
            val btn = Button(this).apply {
                text = preset
                textSize = 12f
                setTextColor(Color.LTGRAY)
                setBackgroundColor(Color.parseColor("#2A2A2A"))
                setOnClickListener { input.setText(preset) }
            }
            presetGroup.addView(btn)
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        var dialogView: View? = null

        val cancelBtn = Button(this).apply {
            text = "Cancel"
            setTextColor(Color.GRAY)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                dialogView?.let { windowManager.removeView(it) }
            }
        }

        val confirmBtn = Button(this).apply {
            text = "Start Autopilot"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00C853"))
            setOnClickListener {
                val goal = input.text.toString().trim()
                if (goal.isNotEmpty()) {
                    coordinator.startObjective(goal)
                }
                dialogView?.let { windowManager.removeView(it) }
            }
        }

        buttonRow.addView(cancelBtn)
        buttonRow.addView(confirmBtn)

        dialogContainer.addView(title)
        dialogContainer.addView(input)
        dialogContainer.addView(TextView(this).apply {
            text = "Quick Presets:"
            setTextColor(Color.GRAY)
            setPadding(0, 16, 0, 8)
        })
        dialogContainer.addView(presetGroup)
        dialogContainer.addView(buttonRow)

        dialogView = dialogContainer
        windowManager.addView(dialogContainer, params)
    }

    override fun onStateChanged(state: AgentState, message: String) {
        if (state == AgentState.OBJECTIVE_COMPLETED || state == AgentState.STOPPED) {
            activeRect = null
            hudOverlayView?.postInvalidate()
        }
    }

    override fun onDecisionFeedback(
        actionType: String,
        targetBox: ScreenRect?,
        confidence: Float,
        latencyMs: Long
    ) {
        activeRect = targetBox
        activeConfidence = confidence
        activeLatencyMs = latencyMs
        hudOverlayView?.postInvalidate()
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator.setListener(null)
        bubbleView?.let { windowManager.removeView(it) }
        hudOverlayView?.let { windowManager.removeView(it) }
    }
}
