package ge.grid.app.tracking

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.google.android.gms.location.*
import ge.grid.app.GridApp
import ge.grid.app.MainActivity
import ge.grid.app.R
import ge.grid.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Foreground only from an explicit in-app user start. Notification controls work when screen is off. */
class TripService : Service() {
    companion object {
        const val START = "ge.grid.app.START"
        const val PAUSE = "ge.grid.app.PAUSE"
        const val RESUME = "ge.grid.app.RESUME"
        const val FINISH = "ge.grid.app.FINISH"
        const val TRIP_ID = "trip_id"
        private const val CHANNEL = "grid_recording"
        private const val NOTIFICATION = 33
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Mutex()
    private lateinit var client: FusedLocationProviderClient
    private lateinit var db: GridDatabase
    private var current: TripEntity? = null
    private var lastFix: Fix? = null
    private var needsAnchor = true
    private var started = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { location ->
                scope.launch(Dispatchers.IO) { persist(location) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = (application as GridApp).database
        client = LocationServices.getFusedLocationProviderClient(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.tracking_channel), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.tracking_channel_description)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification("Starting GPS…", false), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Log.e("GRID", "Foreground service not permitted", e)
            stopSelf(); return START_NOT_STICKY
        }
        val action = intent?.action ?: START // START_STICKY recovery, if OS permits.
        scope.launch {
            gate.withLock {
                when (action) {
                    START -> {
                        val id = intent?.getStringExtra(TRIP_ID)
                        current = if (id == null) db.dao().getActiveTrip() else db.dao().getTrip(id)
                        if (current?.status == "recording") subscribe() else if (current?.status == "paused") updateNotification()
                        else stopSelf()
                    }
                    PAUSE -> pause()
                    RESUME -> resume()
                    FINISH -> finish()
                }
            }
        }
        return START_STICKY
    }

    private fun subscribe() {
        if (started) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        // Re-anchor after all pauses/restarts; never award distance across a recording gap.
        needsAnchor = true
        lastFix = null
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(2000L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            started = true
            updateNotification()
        } catch (e: SecurityException) { Log.w("GRID", "GPS permission revoked", e); stopSelf() }
    }

    private suspend fun pause() {
        val t = current ?: db.dao().getActiveTrip() ?: return
        if (t.status != "recording") return
        client.removeLocationUpdates(callback); started = false
        current = t.copy(status = "paused", pausedAt = System.currentTimeMillis())
        db.dao().updateTrip(current!!)
        updateNotification()
    }
    private suspend fun resume() {
        val t = current ?: db.dao().getActiveTrip() ?: return
        if (t.status != "paused") return
        val now = System.currentTimeMillis()
        current = t.copy(status = "recording", pausedAt = null,
            pausedTotalMs = t.pausedTotalMs + (now - (t.pausedAt ?: now)).coerceAtLeast(0L))
        db.dao().updateTrip(current!!)
        subscribe()
    }
    private suspend fun finish() {
        client.removeLocationUpdates(callback); started = false
        val t = current ?: db.dao().getActiveTrip()
        if (t != null && t.status != "finished") {
            val now = System.currentTimeMillis()
            db.dao().updateTrip(t.copy(status = "finished", endedAt = now, pausedAt = null,
                pausedTotalMs = t.pausedTotalMs + if (t.status == "paused") (now - (t.pausedAt ?: now)).coerceAtLeast(0) else 0))
        }
        current = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private suspend fun persist(location: Location) = gate.withLock {
        val t = current ?: return@withLock
        if (t.status != "recording") return@withLock
        val sample = Fix(location.latitude, location.longitude, location.time, location.accuracy,
            if (location.hasSpeed() && (!location.hasSpeedAccuracy() || location.speedAccuracyMetersPerSecond <= 5f)) location.speed else null,
            if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider)
        val result = TripMath.evaluate(if (needsAnchor) null else lastFix, sample)
        if (!result.accepted) return@withLock
        needsAnchor = false
        // Ignore points with duplicate timestamps, including after process recreation.
        val accepted = db.withTransaction {
            if (db.dao().addPoint(PointEntity(t.id, sample.timestampMs, sample.latitude, sample.longitude,
                    sample.accuracyMeters, sample.speedMps, result.meters)) == -1L) false
            else {
                val credibleSpeed = sample.speedMps?.toDouble()?.takeIf { it in 0.0..65.0 }
                current = t.copy(distanceMeters = t.distanceMeters + result.meters,
                    movingMs = t.movingMs + result.movingMs,
                    maxSpeedMps = listOfNotNull(t.maxSpeedMps, credibleSpeed).maxOrNull(),
                    lastAccuracyMeters = sample.accuracyMeters)
                db.dao().updateTrip(current!!)
                true
            }
        }
        if (accepted) { lastFix = sample; updateNotification() }
    }

    private fun pending(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, TripService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(message: String, paused: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_grid)
            .setContentTitle("GRID · Journey recording").setContentText(message).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        builder.addAction(0, if (paused) "Resume" else "Pause", pending(if (paused) RESUME else PAUSE, 2))
        builder.addAction(0, "Finish", pending(FINISH, 3))
        return builder.build()
    }
    private fun updateNotification() {
        val t = current ?: return
        val paused = t.status == "paused"
        val subtitle = if (paused) "Paused" else "%.2f km recorded · GPS active".format(t.distanceMeters / 1000)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(subtitle, paused))
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
