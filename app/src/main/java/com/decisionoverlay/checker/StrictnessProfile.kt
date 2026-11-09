package com.decisionoverlay.checker

data class StrictnessProfile(
    val level: Int,
    val label: String,
    val scoreThreshold: Int,
    val requiredConfirmations: Int,
    val volumeExpansionRatio: Double,
    val volumeDryUpRatio: Double,
) {
    companion object {
        fun fromLevel(raw: Int): StrictnessProfile = when (raw.coerceIn(0, 4)) {
            0 -> StrictnessProfile(0, "Loose / Aggressive", 1, 1, 1.05, 0.95)
            1 -> StrictnessProfile(1, "Balanced", 2, 2, 1.15, 0.85)
            2 -> StrictnessProfile(2, "Strict", 3, 3, 1.25, 0.75)
            3 -> StrictnessProfile(3, "Very Strict", 4, 4, 1.40, 0.65)
            else -> StrictnessProfile(4, "Ultra-Strict", 5, 5, 1.60, 0.55)
        }
    }
}
