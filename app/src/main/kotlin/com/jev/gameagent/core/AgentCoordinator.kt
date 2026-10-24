package com.jev.gameagent.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.jev.gameagent.input.AccessibilityTouchService
import com.jev.gameagent.input.SwipeDirection
import com.jev.gameagent.jev.*
import com.jev.gameagent.vision.FrameCompressor
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class AgentState {
    IDLE,
    OBSERVING,
    DECIDING,
    EXECUTING,
    OBJECTIVE_COMPLETED,
    STOPPED
}

interface AgentStateListener {
    fun onStateChanged(state: AgentState, message: String)
    fun onDecisionFeedback(
        actionType: String,
        targetBox: ScreenRect?,
        confidence: Float,
        latencyMs: Long
    )
}

class AgentCoordinator(
    private val context: Context,
    private val jevApiClient: JevApiClient
) {

    companion object {
        private const val TAG = "AgentCoordinator"
        private const val MIN_FRAME_INTERVAL_MS = 150L // ~6.6 FPS maximum inference rate
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val safetyManager = SafetyManager()

    var activeObjective: String = ""
        private set

    var parsedObjective: ParsedObjective? = null
        private set

    var currentState: AgentState = AgentState.IDLE
        private set

    private var stateListener: AgentStateListener? = null
    private val isProcessingFrame = AtomicBoolean(false)
    private var lastInferenceTimeMs = 0L

    fun setListener(listener: AgentStateListener?) {
        this.stateListener = listener
    }

    fun startObjective(objective: String) {
        activeObjective = objective
        parsedObjective = ObjectiveParser.parse(objective)
        safetyManager.reset()
        updateState(AgentState.OBSERVING, "Observing screen for objective: $objective")
        Log.i(TAG, "Starting objective: $objective (Parsed: $parsedObjective)")
    }

    fun stopObjective(reason: String) {
        updateState(AgentState.STOPPED, reason)
        safetyManager.triggerEmergencyStop(reason)
        Log.i(TAG, "Objective stopped: $reason")
    }

    fun onNewScreenFrame(bitmap: Bitmap, screenWidth: Int, screenHeight: Int) {
        if (currentState == AgentState.IDLE ||
            currentState == AgentState.STOPPED ||
            currentState == AgentState.OBJECTIVE_COMPLETED
        ) {
            return
        }

        if (safetyManager.isEmergencyStopped) {
            stopObjective("Emergency stop active")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastInferenceTimeMs < MIN_FRAME_INTERVAL_MS) {
            return
        }

        // Avoid queuing concurrent inference requests
        if (!isProcessingFrame.compareAndSet(false, true)) {
            return
        }

        lastInferenceTimeMs = now

        scope.launch {
            try {
                processFrame(bitmap, screenWidth, screenHeight)
            } finally {
                isProcessingFrame.set(false)
            }
        }
    }

    private suspend fun processFrame(bitmap: Bitmap, screenWidth: Int, screenHeight: Int) {
        val startTime = System.currentTimeMillis()
        updateState(AgentState.DECIDING, "Evaluating screen with Jev...")

        val downscaled = FrameCompressor.downscaleBitmap(bitmap, maxDimension = 512)
        val base64Image = FrameCompressor.toBase64Jpeg(downscaled, quality = 70)

        // Build typed questions payload for Jev
        val questions = mapOf(
            "game_status" to QuestionSpec(
                type = "choice",
                instructions = "What is the current screen status?",
                criteria = mapOf(
                    "in_progress" to "active gameplay is running",
                    "level_complete" to "victory, stage cleared, win, reward popup",
                    "game_over" to "defeat, you died, fail, retry screen",
                    "menu" to "main menu, stage selection, pause menu, loading screen"
                )
            ),
            "action_type" to QuestionSpec(
                type = "choice",
                instructions = "What immediate action should be performed to advance the objective?",
                criteria = mapOf(
                    "tap_target" to "tap a specific button, enemy, powerup, or control",
                    "swipe_lane" to "swipe to dodge, move, jump, or turn",
                    "wait" to "no action required right now"
                )
            ),
            "target_box" to QuestionSpec(
                type = "box",
                instructions = "Precise bounding box of the element to interact with or tap"
            ),
            "swipe_direction" to QuestionSpec(
                type = "choice",
                instructions = "If swipe is needed, which direction?",
                criteria = mapOf(
                    "up" to "swipe up",
                    "down" to "swipe down",
                    "left" to "swipe left",
                    "right" to "swipe right",
                    "none" to "no swipe"
                )
            ),
            "objective_fulfilled" to QuestionSpec(
                type = "noul",
                instructions = "Has the user's objective ($activeObjective) been successfully completed?"
            )
        )

        val request = JevDecisionRequest(
            state = mapOf(
                "objective" to activeObjective,
                "screen_width" to screenWidth,
                "screen_height" to screenHeight,
                "image" to base64Image
            ),
            questions = questions
        )

        val result = jevApiClient.decide(request)
        val latencyMs = System.currentTimeMillis() - startTime

        result.onSuccess { response ->
            safetyManager.recordSuccess()
            handleJevDecisions(response, screenWidth, screenHeight, latencyMs)
        }.onFailure { error ->
            Log.e(TAG, "Jev evaluation failed: ${error.message}")
            safetyManager.recordFailure()
            updateState(AgentState.OBSERVING, "Jev query error: ${error.localizedMessage}")
        }
    }

    private fun handleJevDecisions(
        response: JevDecisionResponse,
        screenWidth: Int,
        screenHeight: Int,
        latencyMs: Long
    ) {
        val answers = response.answers ?: return

        // 1. Check completion
        val completionAnswer = answers["objective_fulfilled"]
        if (completionAnswer?.value == true && (completionAnswer.probability ?: 0f) >= 0.85f) {
            updateState(AgentState.OBJECTIVE_COMPLETED, "Objective '$activeObjective' accomplished!")
            stateListener?.onDecisionFeedback("COMPLETED", null, completionAnswer.probability ?: 1f, latencyMs)
            return
        }

        // 2. Game Status check
        val gameStatus = answers["game_status"]?.choice ?: "in_progress"
        val actionType = answers["action_type"]?.choice ?: "wait"
        val targetBoxAnswer = answers["target_box"]
        val targetBox = targetBoxAnswer?.toScreenCoordinates(screenWidth, screenHeight)
        val confidence = answers["action_type"]?.confidence ?: 0.5f

        stateListener?.onDecisionFeedback(actionType, targetBox, confidence, latencyMs)

        if (!safetyManager.canDispatchAction()) {
            return
        }

        val touchService = AccessibilityTouchService.instance
        if (touchService == null || !touchService.isReady()) {
            Log.w(TAG, "Touch Service not bound. Please enable Accessibility Service.")
            return
        }

        updateState(AgentState.EXECUTING, "Action: $actionType ($gameStatus)")

        when (actionType) {
            "tap_target" -> {
                if (targetBox != null) {
                    touchService.tap(targetBox.centerX, targetBox.centerY)
                }
            }
            "swipe_lane" -> {
                val dir = answers["swipe_direction"]?.choice?.lowercase(Locale.ROOT)
                val directionEnum = when (dir) {
                    "up" -> SwipeDirection.UP
                    "down" -> SwipeDirection.DOWN
                    "left" -> SwipeDirection.LEFT
                    "right" -> SwipeDirection.RIGHT
                    else -> null
                }
                if (directionEnum != null) {
                    touchService.swipeDirection(directionEnum, screenWidth, screenHeight)
                }
            }
            "wait" -> {
                // Deliberately hold still
            }
        }
    }

    private fun updateState(newState: AgentState, message: String) {
        currentState = newState
        stateListener?.onStateChanged(newState, message)
    }
}
