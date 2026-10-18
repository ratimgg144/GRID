package ge.grid.app.tracking

import org.junit.Assert.*
import org.junit.Test

class TripMathTest {
    private val a = Fix(41.7151, 44.8271, 1_000_000, 4f, 12f)
    @Test fun realDistanceInTbilisi() {
        val b = Fix(41.7151, 44.8371, 1_060_000, 4f, 13f)
        val s = TripMath.evaluate(a, b)
        assertTrue(s.accepted)
        assertTrue(s.meters in 790.0..870.0)
        assertEquals(60_000L, s.movingMs)
    }
    @Test fun stationaryDriftIsNotDistance() {
        val b = Fix(41.71511, 44.82711, 1_005_000, 5f, 0f)
        assertFalse(TripMath.evaluate(a, b).accepted)
    }
    @Test fun impossibleJumpIsRejected() {
        val b = Fix(42.0, 45.0, 1_005_000, 3f)
        assertFalse(TripMath.evaluate(a, b).accepted)
    }
    @Test fun gapDoesNotGrantDistance() {
        val b = Fix(41.75, 44.85, 1_500_000, 3f)
        val s = TripMath.evaluate(a, b)
        assertTrue(s.accepted)
        assertEquals(0.0, s.meters, 0.001)
        assertTrue(s.reanchor)
    }
    @Test fun mockFixIsRejected() {
        assertFalse(TripMath.evaluate(a, a.copy(timestampMs=1_008_000, mock=true)).accepted)
    }
    @Test fun outOfOrderRejected() {
        assertFalse(TripMath.evaluate(a, a.copy(timestampMs=999_000)).accepted)
    }
    @Test fun distanceNeverIncludesPause() {
        val afterPause = a.copy(timestampMs = 1_500_000, longitude=44.84)
        assertEquals(0.0, TripMath.evaluate(a, afterPause).meters, 0.0)
    }
}
