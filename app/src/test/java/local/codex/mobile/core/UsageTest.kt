package local.codex.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class UsageTest {
    @Test fun weeklyCanBeTheOnlyWindow() {
        assertEquals(listOf("Semanal" to 97.0), includedRateMeters(listOf(RateWindow(10_080, 3.0))))
    }

    @Test fun timeWindowsAreIdentifiedByDuration() {
        assertEquals(listOf("5 h" to 82.0, "Semanal" to 97.0),
            includedRateMeters(listOf(RateWindow(10_080, 3.0), RateWindow(300, 18.0))))
    }

    @Test fun invalidWindowsDoNotGetAPercentage() {
        assertEquals(emptyList<Pair<String, Double>>(),
            includedRateMeters(listOf(RateWindow(300, Double.NaN), RateWindow(60, 1.0))))
    }

    @Test fun contextMatchesCodexCliBaseline() {
        assertEquals(65, contextRemaining(475_000, 172_000).roundToInt())
        assertEquals(100, contextRemaining(475_000, 6_000).roundToInt())
        assertEquals(0, contextRemaining(475_000, 480_000).roundToInt())
    }
}
