package ge.grid.app.tracking

import kotlin.math.*

/** Pure GPS filtering shared by the recorder and JVM tests. No OBD values. */
data class Fix(val latitude: Double, val longitude: Double, val timestampMs: Long,
               val accuracyMeters: Float, val speedMps: Float? = null, val mock: Boolean = false)
data class Segment(val accepted: Boolean, val meters: Double = 0.0, val movingMs: Long = 0,
                   val reason: String = "", val reanchor: Boolean = false)

object TripMath {
    fun haversineMeters(a: Fix, b: Fix): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    fun usable(f: Fix): Boolean = f.latitude in -90.0..90.0 && f.longitude in -180.0..180.0 &&
        f.accuracyMeters.isFinite() && f.accuracyMeters in 0.1f..35f && !f.mock &&
        (f.speedMps == null || (f.speedMps.isFinite() && f.speedMps in 0f..65f))

    fun evaluate(previous: Fix?, next: Fix): Segment {
        if (!usable(next)) return Segment(false, reason = "Low confidence, invalid or mock fix")
        if (previous == null) return Segment(true, reason = "First fix")
        val dt = next.timestampMs - previous.timestampMs
        if (dt < 800) return Segment(false, reason = "Duplicate / out of order")
        // A long gap doesn't imply a straight journey. Re-anchor without awarding kilometres.
        if (dt > 120_000) return Segment(true, reason = "GPS gap", reanchor = true)
        val meters = haversineMeters(previous, next)
        val uncertainty = max(8.0, (previous.accuracyMeters + next.accuracyMeters).toDouble() * 1.5)
        if (meters < uncertainty) return Segment(false, reason = "Stationary drift")
        val speed = meters / (dt / 1000.0)
        if (speed > 65.0 || (next.speedMps != null && abs(speed - next.speedMps) > 20.0 && speed > 25.0))
            return Segment(false, reason = "Implausible jump")
        if (speed < 1.3) return Segment(true, reason = "Slow movement", reanchor = true)
        return Segment(true, meters, dt, "Moving")
    }
}
