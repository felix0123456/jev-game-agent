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

        var ymin = coords[0]
        var xmin = coords[1]
        var ymax = coords[2]
        var xmax = coords[3]

        // Handle 0..1000 scale if returned by vision model
        if (ymin > 1.0f || xmin > 1.0f || ymax > 1.0f || xmax > 1.0f) {
            ymin /= 1000f
            xmin /= 1000f
            ymax /= 1000f
            xmax /= 1000f
        }

        // Clamp safely to screen bounds [0.0, 1.0]
        ymin = ymin.coerceIn(0f, 1f)
        xmin = xmin.coerceIn(0f, 1f)
        ymax = ymax.coerceIn(0f, 1f)
        xmax = xmax.coerceIn(0f, 1f)

        val top = ymin * screenHeight
        val left = xmin * screenWidth
        val bottom = ymax * screenHeight
        val right = xmax * screenWidth

        return ScreenRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            centerX = (left + right) / 2f,
            centerY = (top + bottom) / 2f
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
