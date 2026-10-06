package com.friday.assistant.ui.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.assistant.diag.DiagResult
import com.friday.assistant.diag.DiagStatus
import com.friday.assistant.diag.DiagTest
import com.friday.assistant.ui.DiagnosticsViewModel
import com.friday.assistant.core.LatencyReport
import com.friday.assistant.ui.components.GlassPanel
import com.friday.assistant.ui.theme.LocalEnergy
import com.friday.assistant.ui.components.HudLabel
import com.friday.assistant.ui.theme.FridayColors

@Composable
fun DiagnosticsScreen(vm: DiagnosticsViewModel) {
    val results by vm.results.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val lat by vm.latency.collectAsStateWithLifecycle()
    DiagnosticsContent(vm.tests, results, running, vm::run, vm::runAll, lat)
}

@Composable
fun DiagnosticsContent(
    tests: List<DiagTest>, results: Map<String, DiagResult>, running: String?,
    onRun: (String) -> Unit, onRunAll: () -> Unit, latency: LatencyReport? = null,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            HudLabel("DEVELOPER DIAGNOSTICS")
            Button(onClick = onRunAll, enabled = running == null) { Text("RUN ALL") }
        }
        Text("실기기 점검용입니다. 각 항목을 개별 실행하세요.", color = FridayColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "latency") { LatencyPanel(latency) }
            items(tests, key = { it.id }) { t ->
                val r = results[t.id]
                GlassPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = FridayColors.Text, fontSize = 15.sp)
                            Text(r?.detail ?: t.hint, color = FridayColors.TextDim, fontSize = 12.sp)
                            if (r != null) Text(
                                when (r.status) { DiagStatus.PASS -> "PASS"; DiagStatus.FAIL -> "FAIL"; DiagStatus.NOT_CONFIGURED -> "NOT CONFIGURED"; DiagStatus.DEVICE_TEST_REQUIRED -> "DEVICE TEST REQUIRED" },
                                color = when (r.status) { DiagStatus.PASS -> FridayColors.Ok; DiagStatus.FAIL -> FridayColors.Error; DiagStatus.NOT_CONFIGURED -> FridayColors.TextDim; DiagStatus.DEVICE_TEST_REQUIRED -> LocalEnergy.current.secondary },
                                fontSize = 12.sp, letterSpacing = 2.sp,
                            )
                        }
                        if (running == t.id) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else OutlinedButton(onClick = { onRun(t.id) }, enabled = running == null) { Text("TEST") }
                    }
                }
            }
        }
    }
}

/** Real timings of the last answered interaction. "—" means that stage was not measured (e.g. typed input has no STT). */
@Composable
fun LatencyPanel(l: LatencyReport?) {
    fun f(v: Long?) = v?.let { "$it ms" } ?: "—"
    GlassPanel(Modifier.fillMaxWidth()) {
        Column {
            HudLabel("LATENCY (LAST ANSWER)")
            if (l == null) Text("아직 측정된 응답이 없습니다. FRIDAY와 대화한 뒤 확인하세요.", color = FridayColors.TextDim, fontSize = 12.sp)
            else listOf("Wake latency" to l.wake, "STT latency" to l.stt, "AI latency" to l.ai, "Command latency" to l.command, "TTS first-audio" to l.ttsFirstAudio, "Total response" to l.total)
                .forEach { (name, v) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, color = FridayColors.TextDim, fontSize = 13.sp); Text(f(v), color = FridayColors.Text, fontSize = 13.sp)
                } }
        }
    }
}
