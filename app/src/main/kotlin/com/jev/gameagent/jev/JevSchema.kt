package com.jev.gameagent.jev

import com.google.gson.annotations.SerializedName

/**
 * Request payload for TypeSafe AI Jev System One decision engine.
 */
data class JevDecisionRequest(
    val model: String = "typesafe/jev-latest",
    val state: Any,
    val questions: Map<String, QuestionSpec>
)

data class QuestionSpec(
    val type: String, // "choice", "score", "noul", "box"
    val instructions: String,
    val criteria: Any? = null // Map<String, String> or List<String>
)

/**
 * Response payload returned by TypeSafe AI Jev API.
 */
data class JevDecisionResponse(
    val id: String?,
    val model: String?,
    val answers: Map<String, JevAnswer>?,
    val usage: JevUsage?
)

data class JevAnswer(
    val type: String?,
    val choice: String?,
    val score: String?,
    val value: Boolean?, // For "noul" (Yes/No)
    @SerializedName("box")
    val boxCoordinates: List<Float>?, // Normalized [ymin, xmin, ymax, xmax]
    val probabilities: Map<String, Float>?,
    val probability: Float?,
    val confidence: Float?
) {
    /**
     * Converts normalized [ymin, xmin, ymax, xmax] into screen pixel coordinates.
     */
    fun toScreenCoordinates(screenWidth: Int, screenHeight: Int): ScreenRect? {
        val coords = boxCoordinates ?: return null
        if (coords.size < 4) return null

        val ymin = coords[0] * screenHeight
        val xmin = coords[1] * screenWidth
        val ymax = coords[2] * screenHeight
        val xmax = coords[3] * screenWidth

        return ScreenRect(
            left = xmin,
            top = ymin,
            right = xmax,
            bottom = ymax,
            centerX = (xmin + xmax) / 2f,
            centerY = (ymin + ymax) / 2f
        )
    }
}

data class ScreenRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val centerX: Float,
    val centerY: Float
)

data class JevUsage(
    @SerializedName("prompt_tokens") val promptTokens: Int?,
    @SerializedName("completion_tokens") val completionTokens: Int?,
    @SerializedName("total_tokens") val totalTokens: Int?
)
