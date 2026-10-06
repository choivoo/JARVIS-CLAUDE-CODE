package com.jarvis.assistant.ui.holo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.gesture.GestureManager
import com.jarvis.assistant.gesture.HandPose
import kotlin.math.cos
import kotlin.math.sin

/** Device tilt as roughly -1..1 on each axis, relative to how the phone was held when the screen opened. */
@Composable
fun rememberTilt(enabled: Boolean): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(enabled) {
        if (!enabled) {
            tilt.value = Offset.Zero
            return@DisposableEffect onDispose { }
        }
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        var baseRoll = Float.NaN
        var basePitch = Float.NaN
        val listener = object : SensorEventListener {
            private val rot = FloatArray(9)
            private val ori = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                SensorManager.getOrientation(rot, ori)
                val pitch = ori[1]
                val roll = ori[2]
                if (baseRoll.isNaN()) {
                    baseRoll = roll
                    basePitch = pitch
                }
                val tx = ((roll - baseRoll) / 0.45f).coerceIn(-1f, 1f)
                val ty = ((pitch - basePitch) / 0.45f).coerceIn(-1f, 1f)
                val p = tilt.value
                tilt.value = Offset(p.x + (tx - p.x) * 0.15f, p.y + (ty - p.y) * 0.15f)   // low-pass
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (sensor != null) sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensors.unregisterListener(listener) }
    }
    return tilt
}

/** Perspective "projector floor": converging lines that make the windows feel like they float above a base. */
@Composable
fun HoloFloor(accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val horizon = size.height * 0.62f
        val vx = size.width / 2f
        val lines = 14
        for (i in -lines..lines) {
            val bottomX = vx + i * size.width * 0.12f
            drawLine(
                Brush.verticalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.16f)), startY = horizon, endY = size.height),
                Offset(vx + i * size.width * 0.012f, horizon), Offset(bottomX, size.height), strokeWidth = 1f,
            )
        }
        var y = horizon
        var step = 6.dp.toPx()
        while (y < size.height) {
            drawLine(accent.copy(alpha = 0.10f + 0.10f * ((y - horizon) / (size.height - horizon))), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
            step *= 1.35f
        }
    }
}

/** The air cursor: a reticle that tightens while pinching and fills while a point is held (dwell click). */
@Composable
fun HandCursor(pointerFlow: StateFlow<GestureManager.Pointer>, accent: Color, time: () -> Float, modifier: Modifier = Modifier) {
    val pointer by pointerFlow.collectAsState()
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            if (!pointer.visible) return@Canvas
            val time = time()
            val c = Offset(pointer.x * size.width, pointer.y * size.height)
            val base = 20.dp.toPx()
            val r = if (pointer.pinching) base * 0.55f else base
            val pulse = 1f + 0.06f * sin(time * 6f)
            drawCircle(accent.copy(alpha = 0.18f), radius = r * 1.9f * pulse, center = c)
            drawCircle(accent, radius = r, center = c, style = Stroke(width = 2.5f.dp.toPx()))
            // crosshair ticks
            for (k in 0 until 4) {
                val a = k * Math.PI / 2 + time * 0.8
                val inner = r * 1.15f
                val outer = r * 1.55f
                drawLine(
                    accent.copy(alpha = 0.8f),
                    Offset(c.x + cos(a).toFloat() * inner, c.y + sin(a).toFloat() * inner),
                    Offset(c.x + cos(a).toFloat() * outer, c.y + sin(a).toFloat() * outer),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            if (pointer.pinching) drawCircle(Color.White.copy(alpha = 0.9f), radius = 5.dp.toPx(), center = c)
            else drawCircle(accent, radius = 3.dp.toPx(), center = c)
            if (pointer.dwell > 0f) {
                drawArc(
                    Color.White, startAngle = -90f, sweepAngle = 360f * pointer.dwell, useCenter = false,
                    topLeft = Offset(c.x - r * 1.3f, c.y - r * 1.3f),
                    size = androidx.compose.ui.geometry.Size(r * 2.6f, r * 2.6f),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        if (pointer.visible) {
            val label = when {
                pointer.pinching -> "GRAB"
                pointer.pose == HandPose.POINT -> "POINT"
                pointer.pose == HandPose.OPEN_PALM -> "PALM"
                pointer.pose == HandPose.FIST -> "FIST"
                pointer.pose == HandPose.VICTORY -> "VICTORY"
                pointer.pose == HandPose.THUMBS_UP -> "OK"
                else -> ""
            }
            if (label.isNotEmpty()) {
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                    Text(
                        label, color = accent, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                        modifier = Modifier.offset(x = maxWidth * pointer.x + 26.dp, y = maxHeight * pointer.y + 20.dp),
                    )
                }
            }
        }
    }
}
