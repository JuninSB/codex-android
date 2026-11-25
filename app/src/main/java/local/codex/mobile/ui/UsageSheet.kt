package local.codex.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import local.codex.mobile.core.MobileState
import local.codex.mobile.core.RateWindow
import local.codex.mobile.core.includedRateMeters
import local.codex.mobile.core.contextRemaining
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun UsageSheet(state: MobileState, dismiss: () -> Unit) {
    val snapshot = state.rateLimits?.let { limits ->
        limits.optJSONObject("rateLimitsByLimitId")?.optJSONObject("codex")
            ?: limits.optJSONObject("rateLimits")
    }
    val windows = listOfNotNull(snapshot?.optJSONObject("primary"), snapshot?.optJSONObject("secondary"))
        .map { RateWindow(it.optInt("windowDurationMins"), it.optDouble("usedPercent")) }
    val context = state.chat?.optJSONObject("usage")
    val contextWindow = context?.optLong("modelContextWindow", -1) ?: -1
    val contextUsed = context?.optJSONObject("last")?.optLong("totalTokens", -1) ?: -1
    val rows = buildList {
        addAll(includedRateMeters(windows))
        if (contextWindow > 0 && contextUsed >= 0) add("Contexto" to contextRemaining(contextWindow, contextUsed))
    }

    ModalBottomSheet(onDismissRequest = dismiss, containerColor = Ink.Surface) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            Text("Uso restante", style = MaterialTheme.typography.titleLarge)
            rows.forEach { (label, remaining) ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f))
                        Text("${remaining.roundToInt()}%", color = Ink.Muted)
                    }
                    LinearProgressIndicator(
                        progress = { (remaining / 100).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        color = Ink.Accent,
                        trackColor = Ink.Divider,
                        gapSize = 0.dp,
                        drawStopIndicator = {}
                    )
                }
            }
            if (state.rateLimitsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (rows.isEmpty()) Text("Sem dados de uso disponíveis.", color = Ink.Muted)
        }
    }
}
