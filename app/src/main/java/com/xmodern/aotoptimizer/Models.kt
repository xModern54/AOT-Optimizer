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

// --- MODELS ---
data class AppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    var status: String = "loading...",
    val isNew: Boolean = false,
    var size: String = "...",
    var framework: String = "Native" // New field
)
