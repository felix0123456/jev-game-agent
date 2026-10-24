package com.jev.gameagent.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*
import com.jev.gameagent.JevApplication
import com.jev.gameagent.core.AgentCoordinator
import com.jev.gameagent.core.AgentState
import com.jev.gameagent.core.AgentStateListener
import com.jev.gameagent.jev.ScreenRect
import com.jev.gameagent.preset.ExecutionPolicy
import com.jev.gameagent.preset.GoalPreset
import com.jev.gameagent.preset.PolicyType
import com.jev.gameagent.vision.ScreenCaptureService

class FloatingOverlayService : Service(), AgentStateListener {

    companion object {
        private const val TAG = "FloatingOverlayService"

        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var hudOverlayView: View? = null
    private var activeRect: ScreenRect? = null
    private var activeConfidence: Float = 0f
    private var activeLatencyMs: Long = 0L

    private var statusTextView: TextView? = null

    private val coordinator: AgentCoordinator
        get() = JevApplication.instance.coordinator

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        coordinator.setListener(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted; cannot display floating HUD")
            return
        }

        try {
            createBubbleView()
            createHudOverlayView()
        } catch (e: Exception) {
            Log.e(TAG, "Failed creating overlay views", e)
        }
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
            x = 30
            y = 150
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 10)
            setBackgroundColor(Color.parseColor("#EE181818"))
        }

        // Top Row: Brand & Status Summary + Close ✕ button
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val icon = TextView(this).apply {
            text = "⚡ JEV"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 8, 0)
        }

        val status = TextView(this).apply {
            text = "Ready (Tap GOAL/PRESET)"
            setTextColor(Color.LTGRAY)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusTextView = status

        val closeBtn = TextView(this).apply {
            text = " ✕ "
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(12, 0, 4, 0)
            setOnClickListener {
                Log.i(TAG, "User tapped close ✕ on HUD")
                ScreenCaptureService.stopAll(this@FloatingOverlayService)
            }
        }

        topRow.addView(icon)
        topRow.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(closeBtn)

        // Bottom Row: Mini Controls (⏮, ⏭, GOAL, STOP)
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
        }

        val prevBtn = Button(this).apply {
            text = "⏮"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#37474F"))
            textSize = 11f
            setPadding(12, 4, 12, 4)
            setOnClickListener {
                coordinator.prevStep()
            }
        }

        val skipBtn = Button(this).apply {
            text = "⏭"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#37474F"))
            textSize = 11f
            setPadding(12, 4, 12, 4)
            setOnClickListener {
                coordinator.skipStep()
            }
        }

        val goalBtn = Button(this).apply {
            text = "PRESET"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2979FF"))
            textSize = 11f
            setPadding(14, 4, 14, 4)
            setOnClickListener {
                showObjectiveInputDialog()
            }
        }

        val stopBtn = Button(this).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D50000"))
            textSize = 11f
            setPadding(14, 4, 14, 4)
            setOnClickListener {
                coordinator.stopObjective("User pressed floating STOP")
            }
        }

        controlRow.addView(prevBtn)
        controlRow.addView(skipBtn)
        controlRow.addView(goalBtn)
        controlRow.addView(stopBtn)

        container.addView(topRow)
        container.addView(controlRow)

        // Draggable touch listener
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
                        try {
                            windowManager.updateViewLayout(container, params)
                        } catch (e: Exception) {}
                        return true
                    }
                }
                return false
            }
        })

        bubbleView = container
        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating bubble view", e)
        }
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
                textSize = 32f
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
        try {
            windowManager.addView(hudView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add HUD overlay view", e)
        }
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
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.parseColor("#212121"))
        }

        val title = TextView(this).apply {
            text = "Select Goal Sequence / Preset"
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }

        val presets = JevApplication.instance.presetRepository.getPresets()
        val presetListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        var dialogView: View? = null

        for (preset in presets) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 16)
                setBackgroundColor(Color.parseColor("#2C2C2C"))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 12)
                layoutParams = lp
            }

            val pTitle = TextView(this).apply {
                text = "▶ ${preset.name} (${preset.steps.size} steps)"
                setTextColor(Color.parseColor("#00E676"))
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
            }

            val pDesc = TextView(this).apply {
                text = "${preset.defaultPolicy.getSummary()} • ${preset.steps.joinToString(" ➔ ") { it.name }}"
                setTextColor(Color.GRAY)
                textSize = 11f
                setPadding(0, 4, 0, 8)
            }

            card.addView(pTitle)
            card.addView(pDesc)

            card.setOnClickListener {
                coordinator.startPreset(preset, preset.defaultPolicy)
                dialogView?.let { windowManager.removeView(it) }
            }

            presetListContainer.addView(card)
        }

        // Custom Manual Goal Input Option
        val customInputLabel = TextView(this).apply {
            text = "Or enter a custom single goal:"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(0, 12, 0, 6)
        }

        val input = EditText(this).apply {
            hint = "e.g., finish this mission, survive 5 minutes..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setText(coordinator.activeObjective.ifEmpty { "finish this level" })
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 16, 0, 0)
        }

        val cancelBtn = Button(this).apply {
            text = "Close"
            setTextColor(Color.GRAY)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                dialogView?.let { windowManager.removeView(it) }
            }
        }

        val customRunBtn = Button(this).apply {
            text = "Run Custom"
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
        buttonRow.addView(customRunBtn)

        dialogContainer.addView(title)
        dialogContainer.addView(presetListContainer)
        dialogContainer.addView(customInputLabel)
        dialogContainer.addView(input)
        dialogContainer.addView(buttonRow)

        dialogView = dialogContainer
        try {
            windowManager.addView(dialogContainer, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed showing preset dialog", e)
        }
    }

    override fun onStateChanged(state: AgentState, message: String) {
        mainHandler.post {
            when (state) {
                AgentState.OBJECTIVE_COMPLETED -> {
                    activeRect = null
                    hudOverlayView?.postInvalidate()
                    statusTextView?.text = "🎉 Done! ($message)"
                }
                AgentState.STOPPED -> {
                    activeRect = null
                    hudOverlayView?.postInvalidate()
                    statusTextView?.text = "Stopped"
                }
                AgentState.PAUSED_TIMEOUT -> {
                    statusTextView?.text = "⚠️ TIMED OUT (Paused)"
                }
                AgentState.EXECUTING -> {
                    // Retain step counter title
                }
                else -> {}
            }
        }
    }

    override fun onSequenceProgress(
        cycle: Int,
        totalCycles: Int?,
        stepIndex: Int,
        totalSteps: Int,
        stepName: String,
        statusText: String
    ) {
        mainHandler.post {
            val cycleStr = if (totalCycles != null) "C$cycle/$totalCycles" else "C$cycle"
            val text = "[$cycleStr] ${stepIndex + 1}/$totalSteps: $stepName"
            statusTextView?.text = text
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
        try {
            bubbleView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {}
        try {
            hudOverlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {}
    }
}
