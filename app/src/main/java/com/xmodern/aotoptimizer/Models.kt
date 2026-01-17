package com.xmodern.aotoptimizer

import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color

// --- COLORS ---
val DeepBlack = Color(0xFF0A0A0A)
val DarkSurface = Color(0xFF141414)
val NeonBlue = Color(0xFF00E5FF)
val NeonGreen = Color(0xFF00E676)
val NeonOrange = Color(0xFFFF9100)
val NeonRed = Color(0xFFFF1744)
val NeonPurple = Color(0xFFD500F9)
val NeonCyan = Color(0xFF00E5FF)

// --- ENUMS ---
enum class SortOption(val label: String) {
    NAME("Name (A-Z)"),
    STATUS("Optimization Status"),
    SIZE("Artifact Size")
}

// --- MODELS ---
data class AppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    var status: String = "loading...",
    val isNew: Boolean = false,
    var size: String = "...",
    var sizeBytes: Long = -1L, // For sorting
    var framework: String = "Native",
    var complexity: String = "scanning..." // For BottomSheet
) {
    val statusPriority: Int
        get() = when {
            status.contains("everything") -> 4
            status.contains("speed") -> 3
            status.contains("quicken") || status.contains("verify") -> 2
            status == "loading..." -> 1
            else -> 0
        }
}