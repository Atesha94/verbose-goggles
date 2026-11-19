package app.tempo.wear

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import app.tempo.core.PhaseType
import app.tempo.core.TimerStatus
import app.tempo.core.TimerType
import app.tempo.core.totalDurationSec
import app.tempo.wear.sync.DataLayerSync
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* timer works either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DataLayerSync.load(this)
        lifecycleScope.launch {
            DataLayerSync.commands.collectLatest { cmd ->
                val svc = WorkoutService.instance
                when (cmd.action) {
                    "start" -> cmd.templateId?.let { WorkoutService.start(this@MainActivity, it) }
                    "pause", "resume" -> svc?.pauseResume()
                    "skip" -> svc?.skip()
                    "back" -> svc?.back()
                    "stop" -> svc?.stopWorkout()
                }
            }
        }
        setContent { MaterialTheme { App(onStart = ::startWorkout) } }
    }

    private fun startWorkout(templateId: String) {
        permissionRequest.launch(arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.POST_NOTIFICATIONS))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WorkoutService.start(this, templateId)
    }
}

@Composable
private fun App(onStart: (String) -> Unit) {
    val ui by WorkoutService.ui.collectAsState()
    if (ui.view != null && ui.view?.status != TimerStatus.COMPLETED) WorkoutScreen(ui) else TemplateList(onStart)
}

@Composable
private fun TemplateList(onStart: (String) -> Unit) {
    val templates by DataLayerSync.templates.collectAsState()
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        items(templates) { t ->
            val total = totalDurationSec(t)
            Chip(
                onClick = { onStart(t.id) },
                label = { Text(t.name, fontWeight = FontWeight.Bold) },
                secondaryLabel = { Text("%d:%02d".format(total / 60, total % 60)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun phaseColor(type: PhaseType?) = when (type) {
    PhaseType.WORK -> Color(0xFFFF5C3D)
    PhaseType.REST, PhaseType.SET_REST -> Color(0xFF4CD98C)
    PhaseType.WARMUP -> Color(0xFFFFD140)
    PhaseType.COOLDOWN -> Color(0xFF66B3FF)
    null -> Color.White
}

@Composable
private fun WorkoutScreen(ui: WorkoutService.UiState) {
    val v = ui.view ?: return
    val phase = v.phase
    val secs = ((v.remainingMs + 999) / 1000).toInt()
    val svc = WorkoutService.instance
    val phaseLabel = when (phase?.type) {
        PhaseType.WARMUP -> stringResource(R.string.phase_warmup)
        PhaseType.WORK -> stringResource(R.string.phase_work)
        PhaseType.REST -> stringResource(R.string.phase_rest)
        PhaseType.SET_REST -> stringResource(R.string.phase_set_rest)
        PhaseType.COOLDOWN -> stringResource(R.string.phase_cooldown)
        null -> ""
    }
    val timeLeftDesc = stringResource(R.string.a11y_time_left, secs)
    Column(
        Modifier.fillMaxSize().background(phaseColor(phase?.type)).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(phaseLabel.uppercase(), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text("%d:%02d".format(secs / 60, secs % 60), color = Color.Black, fontSize = 52.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.semantics { contentDescription = timeLeftDesc })
        Row(verticalAlignment = Alignment.CenterVertically) {
            phase?.let { Text(stringResource(R.string.round_of, it.round, it.rounds), color = Color.Black, fontWeight = FontWeight.Bold) }
            ui.heartRate?.let {
                Spacer(Modifier.width(8.dp))
                val d = stringResource(R.string.a11y_heart_rate, it)
                Text("♥ $it", color = Color.Black, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = d })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Control("⏮", stringResource(R.string.a11y_back)) { svc?.back() }
            Control(if (v.status == TimerStatus.RUNNING) "⏸" else "▶",
                stringResource(if (v.status == TimerStatus.RUNNING) R.string.a11y_pause else R.string.a11y_resume)) { svc?.pauseResume() }
            Control("⏭", stringResource(R.string.a11y_skip)) { svc?.skip() }
        }
        if (ui.template?.type == TimerType.AMRAP) {
            Chip(onClick = { svc?.tapRound() }, label = { Text(stringResource(R.string.amrap_tap, v.manualRounds)) })
        }
        Control("✕", stringResource(R.string.a11y_stop)) { svc?.stopWorkout() }
    }
}

@Composable
private fun Control(symbol: String, description: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(backgroundColor = Color.Black),
        modifier = Modifier.semantics { contentDescription = description }) {
        Text(symbol, color = Color.White, fontSize = 18.sp)
    }
}
