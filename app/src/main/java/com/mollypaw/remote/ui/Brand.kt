package com.mollypaw.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mollypaw.remote.data.ConnState

object MollyColors {
    val DeepBrown = Color(0xFF6B4226)
    val MidBrown = Color(0xFF8B5E3C)
    val LightBrown = Color(0xFFA67B5B)
    val Tan = Color(0xFFD2B48C)
    val Cream = Color(0xFFFFF8F0)
    val White = Color(0xFFFFFFFF)
    val WarmGray = Color(0xFFF5EDE3)
    val Orange = Color(0xFFD4823A)
    val Ink = Color(0xFF3A2A1A)
    val Error = Color(0xFFA94442)
    val Warning = Color(0xFFA8622A)
}

fun stateLabel(state: ConnState): String = when (state) {
    ConnState.CONNECTED -> "已连接"
    ConnState.CONNECTING -> "连接中"
    ConnState.RECONNECTING -> "重连中"
    ConnState.ERROR -> "连接出错"
    ConnState.DISCONNECTED -> "未连接"
}

fun stateColor(state: ConnState): Color = when (state) {
    ConnState.CONNECTED -> MollyColors.Orange
    ConnState.CONNECTING -> MollyColors.Tan
    ConnState.RECONNECTING -> MollyColors.Warning
    ConnState.ERROR -> MollyColors.Error
    ConnState.DISCONNECTED -> MollyColors.LightBrown
}

@Composable
fun StatusDot(state: ConnState) {
    Box(
        Modifier
            .size(9.dp)
            .background(stateColor(state), CircleShape)
    )
}
