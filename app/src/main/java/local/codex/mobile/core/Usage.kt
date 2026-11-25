package local.codex.mobile.core

private const val CODEX_CONTEXT_BASELINE = 12_000L

internal data class RateWindow(val minutes: Int, val usedPercent: Double)

internal fun includedRateMeters(windows: List<RateWindow>): List<Pair<String, Double>> =
    listOf(300 to "5 h", 10_080 to "Semanal").mapNotNull { (minutes, label) ->
        val used = windows.firstOrNull { it.minutes == minutes }?.usedPercent
        if (used != null && used.isFinite()) label to (100 - used).coerceIn(0.0, 100.0) else null
    }

internal fun contextRemaining(window: Long, latestTotal: Long): Double {
    val usable = window - CODEX_CONTEXT_BASELINE
    if (usable <= 0) return 0.0
    val used = (latestTotal - CODEX_CONTEXT_BASELINE).coerceAtLeast(0)
    return (100.0 * (usable - used) / usable).coerceIn(0.0, 100.0)
}
