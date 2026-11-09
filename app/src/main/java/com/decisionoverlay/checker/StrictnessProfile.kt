package com.decisionoverlay.checker

data class StrictnessProfile(
    val level: Int,
    val label: String,
    val threshold: Int,
    val targetBuffer: Double,
    val lockoutMinutes: Int,
) {
    companion object {
        fun fromLevel(raw: Int): StrictnessProfile = when (raw.coerceIn(0, 4)) {
            0 -> StrictnessProfile(0, "Less Strict", 1, 100.0, 1)
            1 -> StrictnessProfile(1, "Moderately Loose", 2, 150.0, 2)
            2 -> StrictnessProfile(2, "Balanced", 2, 200.0, 3)
            3 -> StrictnessProfile(3, "More Strict", 4, 300.0, 4)
            else -> StrictnessProfile(4, "Maximum Strictness", 5, 400.0, 5)
        }
    }
}
