package com.jev.gameagent.preset

import java.util.UUID

enum class PolicyType {
    RUN_ONCE,
    REPEAT_COUNT,
    DURATION_MINUTES
}

data class ExecutionPolicy(
    val type: PolicyType = PolicyType.RUN_ONCE,
    val count: Int = 1,
    val durationMinutes: Int = 15
) {
    fun getSummary(): String = when (type) {
        PolicyType.RUN_ONCE -> "Run 1 time"
        PolicyType.REPEAT_COUNT -> "Repeat $count times"
        PolicyType.DURATION_MINUTES -> "Run for $durationMinutes min"
    }
}

data class GoalStep(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val prompt: String,
    val timeoutSeconds: Int = 180
)

data class GoalPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val steps: List<GoalStep> = emptyList(),
    val defaultPolicy: ExecutionPolicy = ExecutionPolicy(),
    val cycleDelaySeconds: Int = 3
) {
    companion object {
        fun createStarterPresets(): List<GoalPreset> {
            return listOf(
                GoalPreset(
                    id = "starter_level_grind",
                    name = "Level Progression Loop",
                    description = "Plays the active level, navigates victory screens, and clicks Next/Replay.",
                    defaultPolicy = ExecutionPolicy(PolicyType.REPEAT_COUNT, count = 5),
                    cycleDelaySeconds = 3,
                    steps = listOf(
                        GoalStep(
                            name = "Clear Level",
                            prompt = "Defeat the obstacles/enemies and achieve level victory",
                            timeoutSeconds = 240
                        ),
                        GoalStep(
                            name = "Claim Rewards & Continue",
                            prompt = "Tap claim, continue, or next stage button to finish the round",
                            timeoutSeconds = 60
                        )
                    )
                ),
                GoalPreset(
                    id = "starter_daily_routine",
                    name = "Daily Quest & Chest Routine",
                    description = "Collect free chests, claim daily quests, and upgrade equipment.",
                    defaultPolicy = ExecutionPolicy(PolicyType.RUN_ONCE),
                    cycleDelaySeconds = 2,
                    steps = listOf(
                        GoalStep(
                            name = "Claim Free Shop Chests",
                            prompt = "Navigate to the store or rewards tab and collect all free chests",
                            timeoutSeconds = 120
                        ),
                        GoalStep(
                            name = "Collect Daily Quests",
                            prompt = "Open the quests/missions menu and tap claim all completed rewards",
                            timeoutSeconds = 120
                        )
                    )
                ),
                GoalPreset(
                    id = "starter_boss_survive",
                    name = "Boss Battle Survival",
                    description = "Focus strictly on dodging attacks and defeating the stage boss.",
                    defaultPolicy = ExecutionPolicy(PolicyType.DURATION_MINUTES, durationMinutes = 20),
                    cycleDelaySeconds = 4,
                    steps = listOf(
                        GoalStep(
                            name = "Defeat Boss",
                            prompt = "Dodge boss attack patterns and deplete the boss health bar to zero",
                            timeoutSeconds = 300
                        )
                    )
                )
            )
        }
    }
}
