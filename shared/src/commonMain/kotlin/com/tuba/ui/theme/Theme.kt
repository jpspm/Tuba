package com.tuba.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.tuba.domain.OfficialStatus

private val LightColors = lightColorScheme(
    primary = Color(0xFF006A6A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF1F0),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFFB4622B),
    secondaryContainer = Color(0xFFFFDBC9),
    background = Color(0xFFF7FBFA),
    surface = Color(0xFFF7FBFA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5D4),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F4F),
    onPrimaryContainer = Color(0xFF9CF1F0),
    secondary = Color(0xFFFFB68D),
    secondaryContainer = Color(0xFF7A3A0E),
)

object StatusColors {
    val Proper = Color(0xFF2E7D32)
    val Improper = Color(0xFFC62828)
    val NoData = Color(0xFF757575)

    fun of(status: OfficialStatus) = when (status) {
        OfficialStatus.PROPRIA -> Proper
        OfficialStatus.IMPROPRIA -> Improper
        OfficialStatus.SEM_DADOS -> NoData
    }
}

@Composable
fun TubaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
