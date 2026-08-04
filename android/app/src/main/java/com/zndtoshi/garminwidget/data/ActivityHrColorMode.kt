package com.zndtoshi.garminwidget.data

/**
 * Local preference for activity HR chart stroke coloring.
 * Stable [storageValue] strings are persisted in SharedPreferences.
 */
enum class ActivityHrColorMode(val storageValue: String) {
    /** Cool grey/white line with coral only at ≥95% of resolved max HR. */
    WHITE_RED_PEAKS("WHITE_RED_PEAKS"),

    /** Continuous Garmin-like zone ramp as a fraction of resolved max HR. */
    GARMIN_ZONES("GARMIN_ZONES");

    companion object {
        val DEFAULT: ActivityHrColorMode = WHITE_RED_PEAKS

        fun fromStorage(value: String?): ActivityHrColorMode =
            entries.firstOrNull { it.storageValue == value } ?: DEFAULT
    }
}
