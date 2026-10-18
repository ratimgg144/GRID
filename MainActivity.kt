package ge.grid.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import ge.grid.app.data.*
import ge.grid.app.network.GridApi
import ge.grid.app.tracking.TripService
import ge.grid.app.ui.GridRoot
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = (application as GridApp).database.dao()
        setContent {
            val cars by dao.cars().collectAsState(initial = emptyList())
            val trips by dao.trips().collectAsState(initial = emptyList())
            val active by dao.activeTrip().collectAsState(initial = null)
            val total by dao.totalDistance().collectAsState(initial = null)
            var selected by remember { mutableStateOf<String?>(null) }
            var pendingCar by remember { mutableStateOf<String?>(null) }
            var notice by remember { mutableStateOf<String?>(null) }
            val coroutine = rememberCoroutineScope()
            val api = remember { GridApi() }
            val start: (String) -> Unit = { carId ->
                if (active != null) notice = "Finish the active journey first"
                else coroutine.launch {
                    val trip = TripEntity(UUID.randomUUID().toString(), carId, System.currentTimeMillis())
                    try {
                        dao.addTrip(trip)
                        ContextCompat.startForegroundService(this@MainActivity,
                            Intent(this@MainActivity, TripService::class.java).setAction(TripService.START).putExtra(TripService.TRIP_ID, trip.id))
                    } catch (e: Exception) { notice = "Cannot start: ${e.localizedMessage}" }
                }
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) pendingCar?.let(start)
                else notice = "Precise location is required to record a trip"
                pendingCar = null
            }
            val startRequest: (String) -> Unit = { carId ->
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) start(carId)
                else {
                    pendingCar = carId
                    val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                    permission.launch(perms.toTypedArray())
                }
            }
            val serviceAction: (String) -> Unit = { action ->
                try { startService(Intent(this@MainActivity, TripService::class.java).setAction(action)) }
                catch (e: Exception) { notice = e.localizedMessage ?: "Recording action failed" }
            }
            GridRoot(cars = cars, trips = trips, active = active, totalMeters = total ?: 0.0,
                selectedCarId = selected ?: cars.firstOrNull()?.id, onSelected = { selected = it },
                dao = dao, api = api, onStart = startRequest, onAction = serviceAction,
                onNotice = { notice = it }, notice = notice, dismissNotice = { notice = null })
        }
    }
}
