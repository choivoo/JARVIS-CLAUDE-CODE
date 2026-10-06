package com.friday.assistant.ui.ambient

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.friday.assistant.core.CoreState
import com.friday.assistant.ui.components.FridayCore
import com.friday.assistant.ui.components.HudLabel
import com.friday.assistant.ui.theme.FridayColors
import com.friday.assistant.ui.theme.LocalReduceMotion
import kotlinx.coroutines.delay
import java.time.LocalTime

/**
 * Always-on display: pure black, a small core, the time and the state. The whole block drifts a few dp and dims
 * slightly every minute to avoid burn-in. Tap anywhere to leave.
 */
@Composable
fun AmbientScreen(core: CoreState, subtitle: String, subtitleScale: Float, level: () -> Float, onExit: () -> Unit, nowProvider: () -> LocalTime = { LocalTime.now() }) {
    val still = LocalReduceMotion.current
    var minuteTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(still) {
        if (!still) while (true) { delay(60_000); minuteTick++ }
    }
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val now = nowProvider()
    val m = now.minute + minuteTick.toInt()
    val dx = ((m * 7) % 41 - 20).dp
    val dy = ((m * 13) % 61 - 30).dp
    val dim = if (m % 2 == 0) 0.9f else 0.75f

    Box(
        Modifier.fillMaxSize().background(FridayColors.Amoled).clickable(onClickLabel = "Leave ambient mode", onClick = onExit)
            .semantics { contentDescription = "Ambient mode. Tap to leave." },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.offset(dx, dy).alpha(dim).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            FridayCore(core, level, Modifier.width(150.dp))
            Text("%02d:%02d".format(now.hour, now.minute), color = FridayColors.Text, fontSize = 52.sp, fontWeight = FontWeight.Thin, letterSpacing = 4.sp)
            HudLabel(core.name)
            if (subtitle.isNotBlank()) Text(subtitle, color = FridayColors.Text, fontSize = (18 * subtitleScale).sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        }
    }
}
