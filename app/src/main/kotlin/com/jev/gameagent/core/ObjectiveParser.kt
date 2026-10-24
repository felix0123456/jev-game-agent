package com.jev.gameagent.core

import java.util.Locale

data class ParsedObjective(
    val rawText: String,
    val targetType: ObjectiveType,
    val targetIdentifier: String?,
    val detectedGameName: String?
)

enum class ObjectiveType {
    FINISH_LEVEL,
    FINISH_MISSION,
    SURVIVE_DURATION,
    CUSTOM_GOAL
}

object ObjectiveParser {

    private val LEVEL_REGEX = Regex("""(?:level|lvl|stage)\s*([0-9a-zA-Z_-]+)""", RegexOption.IGNORE_CASE)
    private val MISSION_REGEX = Regex("""(?:mission|chapter|quest)\s*([0-9a-zA-Z_-]+)""", RegexOption.IGNORE_CASE)
    private val GAME_IN_REGEX = Regex("""(?:in|on|for)\s+([a-zA-Z0-9\s]+)$""", RegexOption.IGNORE_CASE)

    fun parse(objectiveText: String): ParsedObjective {
        val trimmed = objectiveText.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        val targetType = when {
            lower.contains("level") || lower.contains("stage") -> ObjectiveType.FINISH_LEVEL
            lower.contains("mission") || lower.contains("quest") || lower.contains("chapter") -> ObjectiveType.FINISH_MISSION
            lower.contains("survive") -> ObjectiveType.SURVIVE_DURATION
            else -> ObjectiveType.CUSTOM_GOAL
        }

        val levelMatch = LEVEL_REGEX.find(trimmed)
        val missionMatch = MISSION_REGEX.find(trimmed)
        val gameMatch = GAME_IN_REGEX.find(trimmed)

        val targetId = levelMatch?.groupValues?.getOrNull(1)
            ?: missionMatch?.groupValues?.getOrNull(1)

        val gameName = gameMatch?.groupValues?.getOrNull(1)?.trim()

        return ParsedObjective(
            rawText = trimmed,
            targetType = targetType,
            targetIdentifier = targetId,
            detectedGameName = gameName
        )
    }
}
