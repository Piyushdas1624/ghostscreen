package com.etaegord.ghostscreen.ui.utils

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

@Composable
fun rememberManualFloatAnim(
    targetValue: Float,
    durationMs: Long = 300,
    fps: Int = 60
): State<Float> {
    val state = remember { mutableStateOf(targetValue) }
    
    LaunchedEffect(targetValue) {
        val startValue = state.value
        val diff = targetValue - startValue
        if (abs(diff) < 0.001f) {
            state.value = targetValue
            return@LaunchedEffect
        }
        
        val frameMs = 1000L / fps
        val totalFrames = (durationMs / frameMs).coerceAtLeast(1)
        val step = diff / totalFrames
        
        for (i in 1..totalFrames) {
            if (!isActive) break
            state.value = startValue + step * i
            delay(frameMs)
        }
        state.value = targetValue
    }
    
    return state
}

@Composable
fun rememberManualLoopAnim(
    min: Float,
    max: Float,
    durationMs: Long = 1500,
    fps: Int = 60
): State<Float> {
    val state = remember { mutableStateOf(min) }
    
    LaunchedEffect(Unit) {
        val frameMs = 1000L / fps
        val totalFrames = (durationMs / frameMs).coerceAtLeast(1)
        val step = (max - min) / totalFrames
        var current = min
        var increasing = true
        
        while (isActive) {
            if (increasing) {
                current += step
                if (current >= max) {
                    current = max
                    increasing = false
                }
            } else {
                current -= step
                if (current <= min) {
                    current = min
                    increasing = true
                }
            }
            state.value = current
            delay(frameMs)
        }
    }
    
    return state
}
