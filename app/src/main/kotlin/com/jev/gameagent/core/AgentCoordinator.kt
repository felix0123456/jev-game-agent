package com.jev.gameagent.core

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.jev.gameagent.input.AccessibilityTouchService
import com.jev.gameagent.input.SwipeDirection
import com.jev.gameagent.jev.*
import com.jev.gameagent.preset.ExecutionPolicy
import com.jev.gameagent.preset.GoalPreset
import com.jev.gameagent.preset.GoalStep
import com.jev.gameagent.preset.PolicyType
import com.jev.gameagent.vision.FrameCompressor
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class AgentState {
    IDLE,
    OBSERVING,
    DECIDING,
    EXECUTING,
    PAUSED_TIMEOUT,
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
    fun onSequenceProgress(
        cycle: Int,
        totalCycles: Int?,
        stepIndex: Int,
        totalSteps: Int,
        stepName: String,
        statusText: String
    ) {}
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

    // Sequential Goal Preset Execution State
    var activePreset: GoalPreset? = null
        private set

    var activePolicy: ExecutionPolicy = ExecutionPolicy()
        private set

    var currentCycle: Int = 1
        private set

    var currentStepIndex: Int = 0
        private set

    var stepStartTimeMs: Long = 0L
        private set

    var sessionStartTimeMs: Long = 0L
        private set

    private var isWaitingCycleDelay: Boolean = false
    private var stateListener: AgentStateListener? = null
    private val isProcessingFrame = AtomicBoolean(false)
    private var lastInferenceTimeMs = 0L

    fun setListener(listener: AgentStateListener?) {
        this.stateListener = listener
    }

    // Launch standalone single goal
    fun startObjective(objective: String) {
        val singleStep = GoalStep(name = "Goal", prompt = objective)
        val tempPreset = GoalPreset(
            id = "standalone",
            name = "Manual Goal",
            steps = listOf(singleStep),
            defaultPolicy = ExecutionPolicy(PolicyType.RUN_ONCE)
        )
        startPreset(tempPreset, ExecutionPolicy(PolicyType.RUN_ONCE))
    }

    // Launch structured sequence with repetition or timer policy
    fun startPreset(preset: GoalPreset, policy: ExecutionPolicy = preset.defaultPolicy) {
        activePreset = preset
        activePolicy = policy
        currentCycle = 1
        currentStepIndex = 0
        sessionStartTimeMs = System.currentTimeMillis()
        isWaitingCycleDelay = false
        safetyManager.reset()

        Log.i(TAG, "Starting preset '${preset.name}' with policy ${policy.getSummary()} (${preset.steps.size} steps)")
        activateCurrentStep()
    }

    private fun activateCurrentStep() {
        val preset = activePreset ?: return
        if (preset.steps.isEmpty()) {
            stopObjective("Preset has no steps")
            return
        }

        val step = preset.steps.getOrNull(currentStepIndex) ?: return
        activeObjective = step.prompt
        parsedObjective = ObjectiveParser.parse(step.prompt)
        stepStartTimeMs = System.currentTimeMillis()
        isWaitingCycleDelay = false

        val totalCycles = if (activePolicy.type == PolicyType.REPEAT_COUNT) activePolicy.count else null
        val progressMsg = "Cycle $currentCycle${if (totalCycles != null) "/$totalCycles" else ""} • Step ${currentStepIndex + 1}/${preset.steps.size}: ${step.name}"

        updateState(AgentState.OBSERVING, progressMsg)
        notifySequenceProgress(step.name, progressMsg)
        Log.i(TAG, "Activated step: $progressMsg (Prompt: '${step.prompt}')")
    }

    fun nextStep() {
        val preset = activePreset ?: return
        if (currentStepIndex + 1 < preset.steps.size) {
            currentStepIndex++
            triggerHaptic(short = true)
            activateCurrentStep()
        } else {
            onCycleCompleted()
        }
    }

    fun skipStep() {
        Log.i(TAG, "User skipped current step")
        nextStep()
    }

    fun prevStep() {
        val preset = activePreset ?: return
        if (currentStepIndex > 0) {
            currentStepIndex--
            activateCurrentStep()
        }
    }

    fun resumeFromPause() {
        if (currentState == AgentState.PAUSED_TIMEOUT) {
            stepStartTimeMs = System.currentTimeMillis() // Reset timeout timer
            activateCurrentStep()
        }
    }

    private fun onCycleCompleted() {
        triggerHaptic(short = true)
        val preset = activePreset ?: return

        when (activePolicy.type) {
            PolicyType.RUN_ONCE -> {
                completeWholeSequence("All goals successfully reached!")
            }
            PolicyType.REPEAT_COUNT -> {
                if (currentCycle >= activePolicy.count) {
                    completeWholeSequence("Completed all ${activePolicy.count} cycles!")
                } else {
                    currentCycle++
                    currentStepIndex = 0
                    scheduleNextCycle(preset.cycleDelaySeconds)
                }
            }
            PolicyType.DURATION_MINUTES -> {
                val elapsedMinutes = (System.currentTimeMillis() - sessionStartTimeMs) / (60 * 1000L)
                if (elapsedMinutes >= activePolicy.durationMinutes) {
                    completeWholeSequence("Duration limit reached (${activePolicy.durationMinutes} min, $currentCycle cycles completed)!")
                } else {
                    currentCycle++
                    currentStepIndex = 0
                    scheduleNextCycle(preset.cycleDelaySeconds)
                }
            }
        }
    }

    private fun scheduleNextCycle(delaySeconds: Int) {
        isWaitingCycleDelay = true
        val delayMs = (delaySeconds.coerceAtLeast(1)) * 1000L
        val msg = "Cycle finished! Next cycle in ${delaySeconds}s..."
        updateState(AgentState.OBSERVING, msg)
        notifySequenceProgress("Preparing Next Cycle", msg)

        scope.launch {
            delay(delayMs)
            if (currentState != AgentState.STOPPED && isWaitingCycleDelay) {
                activateCurrentStep()
            }
        }
    }

    private fun completeWholeSequence(summary: String) {
        triggerHaptic(short = false) // Triple celebration vibration
        updateState(AgentState.OBJECTIVE_COMPLETED, summary)
        notifySequenceProgress("Completed", summary)
        Log.i(TAG, "Sequence completed: $summary")
    }

    fun stopObjective(reason: String) {
        updateState(AgentState.STOPPED, reason)
        safetyManager.triggerEmergencyStop(reason)
        notifySequenceProgress("Stopped", reason)
        Log.i(TAG, "Objective stopped: $reason")
    }

    private fun notifySequenceProgress(stepName: String, statusText: String) {
        val totalSteps = activePreset?.steps?.size ?: 1
        val totalCycles = if (activePolicy.type == PolicyType.REPEAT_COUNT) activePolicy.count else null
        stateListener?.onSequenceProgress(
            cycle = currentCycle,
            totalCycles = totalCycles,
            stepIndex = currentStepIndex,
            totalSteps = totalSteps,
            stepName = stepName,
            statusText = statusText
        )
    }

    fun onNewScreenFrame(bitmap: Bitmap, screenWidth: Int, screenHeight: Int) {
        if (currentState == AgentState.IDLE ||
            currentState == AgentState.STOPPED ||
            currentState == AgentState.OBJECTIVE_COMPLETED ||
            currentState == AgentState.PAUSED_TIMEOUT ||
            isWaitingCycleDelay
        ) {
            return
        }

        if (safetyManager.isEmergencyStopped) {
            stopObjective("Emergency stop active")
            return
        }

        val now = System.currentTimeMillis()

        // 1. Check Duration Policy limit
        if (activePolicy.type == PolicyType.DURATION_MINUTES) {
            val elapsedMinutes = (now - sessionStartTimeMs) / (60 * 1000L)
            if (elapsedMinutes >= activePolicy.durationMinutes) {
                completeWholeSequence("Duration limit of ${activePolicy.durationMinutes} min completed ($currentCycle cycles).")
                return
            }
        }

        // 2. Check Step Timeout
        val activeStep = activePreset?.steps?.getOrNull(currentStepIndex)
        if (activeStep != null && activeStep.timeoutSeconds > 0) {
            val stepElapsedSec = (now - stepStartTimeMs) / 1000L
            if (stepElapsedSec > activeStep.timeoutSeconds) {
                triggerHaptic(short = true)
                val timeoutMsg = "Step '${activeStep.name}' timed out after ${activeStep.timeoutSeconds}s. Paused."
                updateState(AgentState.PAUSED_TIMEOUT, timeoutMsg)
                notifySequenceProgress(activeStep.name, timeoutMsg)
                Log.w(TAG, timeoutMsg)
                return
            }
        }

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
            val step = activePreset?.steps?.getOrNull(currentStepIndex)
            Log.i(TAG, "Step completed: '${step?.name ?: activeObjective}'!")
            stateListener?.onDecisionFeedback("COMPLETED", null, completionAnswer.probability ?: 1f, latencyMs)
            nextStep()
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

    private fun triggerHaptic(short: Boolean) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (short) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    // Triple pulse for whole sequence completion
                    val timings = longArrayOf(0, 150, 100, 150, 100, 250)
                    vibrator?.vibrate(VibrationEffect.createWaveform(timings, -1))
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(if (short) 100 else 400)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not trigger haptic feedback", e)
        }
    }
}
