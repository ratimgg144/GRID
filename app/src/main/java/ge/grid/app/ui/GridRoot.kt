package ge.grid.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ge.grid.app.data.*
import ge.grid.app.network.GridApi
import ge.grid.app.tracking.TripService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray

private val Graphite = Color(0xFF111413)
private val Surface = Color(0xFF1D2220)
private val Lime = Color(0xFFCCFA69)
private val Soft = Color(0xFF909D96)
private val White = Color(0xFFF1F4F0)
private val Outline = Color(0xFF343D37)
private val GeorgiaTime = ZoneId.of("Asia/Tbilisi")
private fun fmt(t: Long): String = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.ENGLISH)
    .format(Instant.ofEpochMilli(t).atZone(GeorgiaTime))
private fun km(m: Double) = String.format(Locale.US, "%.2f", m / 1000.0)
private fun duration(ms: Long): String = "%02d:%02d:%02d".format(Locale.US, ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60)
private fun String.whenKa(ka: Boolean, other: String) = if (ka) other else this

@Composable
fun GridRoot(cars: List<CarEntity>, trips: List<TripEntity>, active: TripEntity?, totalMeters: Double,
    selectedCarId: String?, onSelected: (String) -> Unit, dao: GridDao, api: GridApi,
    onStart: (String) -> Unit, onAction: (String) -> Unit,
    onNotice: (String) -> Unit, notice: String?, dismissNotice: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("grid_settings", 0) }
    var ka by remember { mutableStateOf(prefs.getBoolean("ka", false)) }
    var tab by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var detailTrip by remember { mutableStateOf<TripEntity?>(null) }
    var showAuth by remember { mutableStateOf(false) }
    var rankRefresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val selectedCar = cars.firstOrNull { it.id == selectedCarId }
    val palette = darkColorScheme(primary = Lime, onPrimary = Graphite, background = Graphite, onBackground = White,
        surface = Surface, onSurface = White, surfaceVariant = Surface, outline = Outline)
    MaterialTheme(colorScheme = palette, typography = Typography()) {
        Column(Modifier.fillMaxSize().background(Graphite).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 25.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("▦", color = Lime, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.width(10.dp))
                    Text("GRID", fontSize = 23.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Black, color = White)
                }
                Text("GEORGIA   /   01", color = Soft, fontSize = 10.sp, letterSpacing = 2.sp)
            }
            if (detailTrip != null) {
                TripDetail(detailTrip!!, dao, cars.firstOrNull { it.id == detailTrip!!.carId }, ka,
                    onBack = { detailTrip = null }, onDelete = {
                        val id = detailTrip!!.id
                        scope.launch { dao.deleteTrip(id); detailTrip = null; onNotice("Trip deleted locally") }
                    }, onSync = {
                        val trip = detailTrip ?: return@TripDetail
                        scope.launch {
                            try {
                                val status = api.uploadTrip(trip, dao.getPoints(trip.id))
                                dao.updateTrip(trip.copy(synced = true, serverStatus = status))
                                onNotice("Server: $status")
                            } catch (e: Exception) { onNotice(e.localizedMessage ?: "Sync unavailable") }
                        }
                    })
            } else {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> Garage(cars, selectedCar, trips, totalMeters, ka, { showAdd = true }, onSelected, { detailTrip = it })
                        1 -> Drive(active, selectedCar, dao, ka, onStart, onAction, { detailTrip = it })
                        2 -> Rankings(api, ka, rankRefresh, { showAuth = true }, onNotice)
                        3 -> Clubs(ka)
                        else -> Profile(ka = ka, onLanguage = { new -> ka = new; prefs.edit().putBoolean("ka", new).apply() },
                            api = api, cars = cars, trips = trips, dao = dao, distance = totalMeters,
                            recording = active != null, refresh = rankRefresh, onNotice = onNotice,
                            onLogin = { showAuth = true }, onLogout = { api.logout(); rankRefresh++ })
                    }
                }
                NavigationBar(containerColor = Graphite, tonalElevation = 0.dp) {
                    val titles = if (ka) listOf("გარაჟი", "გზა", "რეიტინგი", "კლუბები", "პროფილი")
                        else listOf("Garage", "Drive", "Rankings", "Clubs", "Profile")
                    val icons = listOf(Icons.Default.DirectionsCar, Icons.Default.Route, Icons.Default.Leaderboard, Icons.Default.Groups, Icons.Default.PersonOutline)
                    titles.forEachIndexed { index, title ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index },
                            icon = { Icon(icons[index], title) }, label = { Text(title, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Lime, selectedTextColor = Lime,
                                indicatorColor = Surface, unselectedIconColor = Soft, unselectedTextColor = Soft))
                    }
                }
            }
        }
        if (showAdd) AddCarDialog(ka, onDismiss = { showAdd = false }, onSave = { car ->
            scope.launch { try { dao.addCar(car); onSelected(car.id); showAdd = false } catch (e: Exception) { onNotice(e.localizedMessage ?: "Could not save") } }
        })
        if (showAuth) AuthDialog(api, onDismiss = { showAuth = false }, onNotice = onNotice, onSuccess = { showAuth = false; rankRefresh++ })
        notice?.let { msg -> AlertDialog(onDismissRequest = dismissNotice, title = { Text("GRID") }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = dismissNotice) { Text("OK") } }) }
    }
}

@Composable private fun Eyebrow(text: String) { Text(text.uppercase(Locale.ROOT), color = Lime, fontSize = 10.sp, letterSpacing = 2.8.sp, fontWeight = FontWeight.Bold) }
@Composable private fun Section(text: String, modifier: Modifier = Modifier) { Text(text, modifier, color = White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp) }
@Composable private fun ThinLine() { HorizontalDivider(color = Outline, thickness = 1.dp) }
@Composable private fun Primary(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.height(54.dp), enabled = enabled, shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Graphite)) { Text(text, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp) }
}
@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Column(modifier) {
        Text(value, color = White, fontSize = if (large) 32.sp else 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
        Spacer(Modifier.height(5.dp))
        Text(label, color = Soft, fontSize = 11.sp)
    }
}
@Composable private fun CarHero(car: CarEntity?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(206.dp).clip(RoundedCornerShape(15.dp)).background(Surface), contentAlignment = Alignment.Center) {
        if (car?.photoUri != null) AsyncImage(model = car.photoUri, contentDescription = "Owner provided car photograph",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.DirectionsCar, null, tint = Color(0xFF66766D), modifier = Modifier.size(96.dp))
            Text("ADD YOUR OWN PHOTOGRAPH", color = Soft, fontSize = 10.sp, letterSpacing = 2.sp)
        }
    }
}

@Composable private fun Garage(cars: List<CarEntity>, selected: CarEntity?, trips: List<TripEntity>, total: Double, ka: Boolean,
                               onAdd: () -> Unit, onSelected: (String) -> Unit, onDetail: (TripEntity) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 25.dp, end = 25.dp, bottom = 25.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Eyebrow("YOUR COLLECTION".whenKa(ka, "შენი კოლექცია")); Spacer(Modifier.height(8.dp)); Section("The garage".whenKa(ka, "გარაჟი")) }
                IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add car", tint = Lime) }
            }
        }
        if (cars.isEmpty()) {
            item { CarHero(null); Spacer(Modifier.height(10.dp)); Text("Your garage starts here.".whenKa(ka, "შენი გარაჟი აქ იწყება."), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(9.dp)); Text("Add a real car to begin recording GPS journeys. Specs are owner-entered, not verified.".whenKa(ka,
                    "დაამატე ავტომობილი GPS-მგზავრობების ჩასაწერად. მონაცემები მფლობელის მიერაა შეყვანილი."), color = Soft, fontSize = 13.sp)
                Spacer(Modifier.height(18.dp)); Primary("+  Add your first car".whenKa(ka, "+  მანქანის დამატება"), onAdd, Modifier.fillMaxWidth()) }
        } else {
            item {
                CarHero(selected)
                Spacer(Modifier.height(17.dp))
                Text(selected?.nickname.orEmpty(), color = White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Text("${selected?.year}  ${selected?.make}  ${selected?.model}  ${selected?.trim}", color = Soft, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(27.dp)) {
                    Metric("Recorded in GRID".whenKa(ka, "ჩაწერილი GRID-ში"), "${km(trips.filter { it.carId == selected?.id && it.status == "finished" }.sumOf { it.distanceMeters })} km")
                    Metric("Journeys".whenKa(ka, "მგზავრობა"), "${trips.count { it.carId == selected?.id && it.status == "finished" }}")
                }
            }
            item { Eyebrow("SELECT CAR".whenKa(ka, "აირჩიე მანქანა")); Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cars.forEach { car -> FilterChip(selected = selected?.id == car.id, onClick = { onSelected(car.id) },
                        label = { Text(car.nickname) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lime, selectedLabelColor = Graphite)) }
                    AssistChip(onClick = onAdd, label = { Text("+ Add") })
                }
            }
        }
        item { ThinLine(); Spacer(Modifier.height(6.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Section("Journey log".whenKa(ka, "მგზავრობები")); Text("${trips.count { it.status == "finished" }}", color = Soft)
        } }
        items(trips.filter { it.status == "finished" && (selected == null || it.carId == selected.id) }, key = { it.id }) { trip ->
            Row(Modifier.fillMaxWidth().clickable { onDetail(trip) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(fmt(trip.startedAt), color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(if (trip.synced) "Server ${trip.serverStatus}" else "Private · On device", color = Soft, fontSize = 11.sp) }
                Text("${km(trip.distanceMeters)} km", fontSize = 17.sp, color = Lime, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Default.ChevronRight, null, tint = Soft)
            }
            ThinLine()
        }
        if (trips.none { it.status == "finished" }) item { Text("No journeys recorded yet.".whenKa(ka, "ჯერ არცერთი მგზავრობა არ არის ჩაწერილი."), color = Soft, fontSize = 13.sp) }
    }
}

@Composable private fun Drive(active: TripEntity?, car: CarEntity?, dao: GridDao, ka: Boolean,
                               onStart: (String) -> Unit, onAction: (String) -> Unit, onDetail: (TripEntity) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active?.id) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val points: List<PointEntity> = if (active != null) {
        val live by dao.points(active.id).collectAsState(initial = emptyList())
        live
    } else emptyList()
    val latest = points.lastOrNull()?.takeIf { now - it.timestampMs <= 15_000 && active?.status == "recording" }
    val speed = latest?.gpsSpeedMps?.let { (it * 3.6).toInt().toString() } ?: "—"
    val elapsed = active?.let { (now - it.startedAt - it.pausedTotalMs - if (it.pausedAt != null) now - it.pausedAt else 0).coerceAtLeast(0) } ?: 0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 25.dp)) {
        Eyebrow("LIVE INSTRUMENTS".whenKa(ka, "მიმდინარე მონაცემები")); Spacer(Modifier.height(6.dp))
        Section("Drive".whenKa(ka, "მგზავრობა"))
        Spacer(Modifier.height(29.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(8.dp)).background(if (active?.status == "recording") Lime else Soft))
            Spacer(Modifier.width(10.dp)); Text(if (active == null) "READY" else if (active.status == "paused") "PAUSED" else "RECORDING",
                color = Soft, fontSize = 11.sp, letterSpacing = 2.sp) }
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.Bottom) { Text(speed, color = White, fontSize = 106.sp, lineHeight = 113.sp,
            fontWeight = FontWeight.Light, letterSpacing = (-6).sp); Spacer(Modifier.width(14.dp)); Text("km/h", modifier = Modifier.padding(bottom = 19.dp), color = Soft, fontSize = 17.sp) }
        Text("Phone GPS · not OBD".whenKa(ka, "ტელეფონის GPS · არა OBD"), color = Soft, fontSize = 12.sp)
        Spacer(Modifier.height(36.dp)); ThinLine(); Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Recorded distance".whenKa(ka, "ჩაწერილი მანძილი"), "${km(active?.distanceMeters ?: 0.0)} km", Modifier.weight(1f), true)
            Metric("Elapsed".whenKa(ka, "გასული დრო"), duration(elapsed), Modifier.weight(1f))
        }
        Spacer(Modifier.height(25.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Moving time".whenKa(ka, "მოძრაობის დრო"), duration(active?.movingMs ?: 0), Modifier.weight(1f))
            Metric("GPS accuracy".whenKa(ka, "GPS სიზუსტე"), active?.lastAccuracyMeters?.let { "±${it.toInt()} m" } ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(22.dp)); ThinLine(); Spacer(Modifier.height(20.dp))
        Text(if (car != null) "${car.nickname}   ·   ${car.make} ${car.model}" else "Select a vehicle in Garage".whenKa(ka, "აირჩიე მანქანა გარაჟში"), color = Soft, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        if (active == null) Primary("START JOURNEY".whenKa(ka, "მგზავრობის დაწყება"), { car?.id?.let(onStart) }, Modifier.fillMaxWidth(), enabled = car != null)
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onAction(if (active.status == "paused") TripService.RESUME else TripService.PAUSE) },
                    modifier = Modifier.weight(1f).height(54.dp)) {
                    Text(if (active.status == "paused") "RESUME".whenKa(ka, "გაგრძელება") else "PAUSE".whenKa(ka, "შეჩერება"))
                }
                Primary("FINISH".whenKa(ka, "დასრულება"), { onAction(TripService.FINISH) }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Recording continues with your screen off. Keep your phone secured; never interact while driving. Trips are private by default.".whenKa(ka,
            "ჩაწერა გრძელდება ეკრანის გამორთვისას. მართვის დროს ტელეფონს ნუ გამოიყენებთ. მგზავრობები პირადულია."), color = Soft, fontSize = 12.sp, lineHeight = 19.sp)
    }
}

@Composable private fun TripDetail(trip: TripEntity, dao: GridDao, car: CarEntity?, ka: Boolean,
    onBack: () -> Unit, onDelete: () -> Unit, onSync: () -> Unit) {
    val points by dao.points(trip.id).collectAsState(initial = emptyList())
    var deletePrompt by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 25.dp)) {
        TextButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Text(" Back") }
        Spacer(Modifier.height(10.dp)); Eyebrow("JOURNEY / RECORDED".whenKa(ka, "მგზავრობის ჩანაწერი"))
        Spacer(Modifier.height(9.dp)); Section(fmt(trip.startedAt))
        Spacer(Modifier.height(8.dp)); Text(car?.nickname ?: "Car", color = Soft)
        Spacer(Modifier.height(20.dp))
        RouteMap(points)
        Spacer(Modifier.height(21.dp))
        Row(Modifier.fillMaxWidth()) {
            Metric("Distance in GRID", "${km(trip.distanceMeters)} km", Modifier.weight(1f))
            Metric("Moving", duration(trip.movingMs), Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            Metric("Elapsed", duration((trip.endedAt ?: trip.startedAt) - trip.startedAt - trip.pausedTotalMs), Modifier.weight(1f))
            Metric("Private GPS max", trip.maxSpeedMps?.let { "${(it * 3.6).toInt()} km/h" } ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(19.dp))
        Text("GPS measurements only. Owner-entered car details are not verified. Exact route remains on this device until you explicitly sync; synced routes stay private.".whenKa(ka,
            "მხოლოდ GPS-მონაცემები. მანქანის მონაცემები გადაუმოწმებელია. მარშრუტი ინახება მოწყობილობაზე; სინქრონიზაციის შემთხვევაშიც პირადულია."), color = Soft, fontSize = 12.sp)
        Spacer(Modifier.height(22.dp))
        Primary(if (trip.synced) "SYNCED · ${trip.serverStatus.uppercase()}" else "SYNC TO GRID".whenKa(ka, "სინქრონიზაცია"), onSync, Modifier.fillMaxWidth(), enabled = !trip.synced)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { deletePrompt = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete this trip", color = Color(0xFFE9A19C)) }
    }
    if (deletePrompt) AlertDialog(onDismissRequest = { deletePrompt = false },
        title = { Text("Delete local recording?") }, text = { Text("This permanently removes local route points. Synced copies must be deleted separately from your account.") },
        confirmButton = { TextButton(onClick = { deletePrompt = false; onDelete() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deletePrompt = false }) { Text("Cancel") } })
}

@Composable private fun Rankings(api: GridApi, ka: Boolean, refresh: Int, login: () -> Unit, notice: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf("week") }
    var entries by remember { mutableStateOf<List<GridApi.Standing>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(period, refresh) {
        entries = emptyList()
        if (api.configured && api.signedIn) {
            loading = true
            try { entries = api.rankings(period) } catch (e: Exception) { notice(e.localizedMessage ?: "Leaderboard unavailable") }
            finally { loading = false }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 25.dp)) {
        Eyebrow("VERIFIED SERVER RECORDS".whenKa(ka, "სერვერის ჩანაწერები")); Spacer(Modifier.height(8.dp))
        Section("Rankings".whenKa(ka, "რეიტინგები")); Spacer(Modifier.height(13.dp))
        Text("Recorded in GRID, never vehicle odometer mileage.".whenKa(ka, "GRID-ში ჩაწერილი კმ, არა მანქანის სრული გარბენი."), color = Soft, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            listOf("week" to "7 days", "month" to "Month", "all" to "All time").forEach { (code, name) ->
                FilterChip(selected = period == code, onClick = { period = code }, label = { Text(name) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lime, selectedLabelColor = Graphite))
            }
        }
        Spacer(Modifier.height(27.dp)); ThinLine(); Spacer(Modifier.height(16.dp))
        if (!api.configured) {
            Section("Backend not connected".whenKa(ka, "სერვერი არ არის დაყენებული"))
            Spacer(Modifier.height(8.dp)); Text("Your garage and recording still work offline. Add your own Supabase URL and anon key to enable authentication and live rankings.", color = Soft, fontSize = 13.sp)
        } else if (!api.signedIn) {
            Section("Sign in to compete".whenKa(ka, "შედი ანგარიშზე"))
            Spacer(Modifier.height(8.dp)); Text("Only accepted server-side GPS distance is ranked. Routes remain private.", color = Soft, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp)); Primary("SIGN IN / CREATE ACCOUNT", login, Modifier.fillMaxWidth())
        } else if (loading) CircularProgressIndicator(color = Lime)
        else if (entries.isEmpty()) Text("No qualifying journeys in this period.".whenKa(ka, "ამ პერიოდში ჩანაწერები არ არის."), color = Soft)
        else entries.forEach { entry ->
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${entry.position.toString().padStart(2, '0')}", Modifier.width(47.dp), color = Lime, fontWeight = FontWeight.Bold)
                Text(entry.displayName, Modifier.weight(1f), fontSize = 15.sp)
                Text("${String.format(Locale.US, "%.1f", entry.kilometres)} km", fontWeight = FontWeight.SemiBold)
            }; ThinLine()
        }
    }
}

@Composable private fun Clubs(ka: Boolean) {
    Column(Modifier.fillMaxSize().padding(25.dp)) {
        Eyebrow("COMMUNITY".whenKa(ka, "საზოგადოება")); Spacer(Modifier.height(7.dp)); Section("Clubs".whenKa(ka, "კლუბები"))
        Spacer(Modifier.height(55.dp)); Icon(Icons.Default.Groups, null, tint = Soft, modifier = Modifier.size(80.dp))
        Spacer(Modifier.height(22.dp)); Text("Coming after trusted trip sync", color = White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp)); Text("Club creation, memberships, and collective rankings will follow the private GPS recorder and validated global distance ranking. No placeholder members or fabricated activity.",
            color = Soft, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable private fun Profile(ka: Boolean, onLanguage: (Boolean) -> Unit, api: GridApi, cars: List<CarEntity>, trips: List<TripEntity>, dao: GridDao, distance: Double, recording: Boolean, refresh: Int, onNotice: (String) -> Unit, onLogin: () -> Unit, onLogout: () -> Unit) {
    var optedIn by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf("") }
    var confirmErase by remember { mutableStateOf(false) }
    var confirmExport by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(exportJson.toByteArray(Charsets.UTF_8)) }
            onNotice("Local export saved. Treat it as sensitive GPS location data.")
        } catch (e: Exception) { onNotice(e.localizedMessage ?: "Export failed") }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refresh) { if (api.signedIn) try { optedIn = api.rankingOptIn() } catch (e: Exception) { onNotice(e.localizedMessage ?: "Profile unavailable") } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(25.dp)) {
        Eyebrow("ACCOUNT & PRIVACY".whenKa(ka, "ანგარიში და კონფიდენციალურობა")); Spacer(Modifier.height(7.dp)); Section("Your cockpit".whenKa(ka, "შენი პროფილი"))
        Spacer(Modifier.height(24.dp)); Row(Modifier.fillMaxWidth()) {
            Metric("Trips saved", "${trips.size}", Modifier.weight(1f))
            Metric("GRID kilometres", "${km(distance)}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(33.dp)); ThinLine(); Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ქართული", Modifier.weight(1f), color = White)
            Switch(checked = ka, onCheckedChange = onLanguage)
        }
        Spacer(Modifier.height(18.dp)); ThinLine(); Spacer(Modifier.height(22.dp))
        Text("Social account", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp)); Text(if (!api.configured) "Requires private Supabase project configuration" else if (!api.signedIn) "Not signed in" else "Signed in for this session", color = Soft, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        if (api.signedIn) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Appear in distance rankings", Modifier.weight(1f), fontSize = 13.sp)
            Switch(checked = optedIn, onCheckedChange = { new ->
                scope.launch { try { api.setRankingOptIn(new); optedIn = new } catch (e: Exception) { onNotice(e.localizedMessage ?: "Cannot update ranking preference") } }
            })
        }
        if (api.configured) Primary(if (api.signedIn) "LOG OUT" else "SIGN IN", if (api.signedIn) onLogout else onLogin, Modifier.fillMaxWidth())
        Spacer(Modifier.height(32.dp)); ThinLine(); Spacer(Modifier.height(18.dp))
        Text("PRIVACY BY DESIGN", color = Lime, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(9.dp)); Text("Routes are saved locally. Rankings contain only aggregated, server-accepted kilometres, not live locations. There is no public speed leaderboard. GPS alone cannot verify a car identity.",
            color = Soft, fontSize = 12.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(18.dp))
        OutlinedButton(onClick = { confirmExport = true }, Modifier.fillMaxWidth()) { Text("Export all on-device data (JSON)") }
        TextButton(onClick = { confirmErase = true }, enabled = !recording, modifier = Modifier.fillMaxWidth()) { Text("Erase all on-device garage and trips", color = Color(0xFFE9A19C)) }
        Spacer(Modifier.height(14.dp)); Text("0.1.0 · Local-first milestone", color = Soft, fontSize = 11.sp)
    }
    if (confirmExport) AlertDialog(onDismissRequest = { confirmExport = false },
        title = { Text("Export sensitive GPS locations?") },
        text = { Text("The JSON contains precise trip locations and car information. Store it privately and do not publicly share it.") },
        confirmButton = { TextButton(onClick = {
            confirmExport = false
            scope.launch {
                try {
                    val doc = JSONObject()
                    val c = JSONArray(); cars.forEach { car -> c.put(JSONObject().put("id", car.id).put("nickname", car.nickname)
                        .put("make", car.make).put("model", car.model).put("year", car.year).put("trim", car.trim)
                        .put("fuel", car.fuel).put("modifications", car.modifications)) }
                    doc.put("cars", c)
                    val t = JSONArray(); trips.forEach { trip ->
                        val pts = JSONArray(); dao.getPoints(trip.id).forEach { p ->
                            pts.put(JSONObject().put("t", p.timestampMs).put("lat", p.latitude).put("lon", p.longitude)
                                .put("accuracy_m", p.accuracyMeters))
                        }
                        t.put(JSONObject().put("id", trip.id).put("car_id", trip.carId).put("started_ms", trip.startedAt)
                            .put("ended_ms", trip.endedAt).put("distance_m", trip.distanceMeters).put("points", pts))
                    }
                    exportJson = doc.put("trips", t).toString(2)
                    exportLauncher.launch("grid-data.json")
                } catch (e: Exception) { onNotice(e.localizedMessage ?: "Could not prepare export") }
            }
        }) { Text("EXPORT") } }, dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("CANCEL") } })
    if (confirmErase) AlertDialog(onDismissRequest = { confirmErase = false },
        title = { Text("Erase all local GRID data?") }, text = { Text("Irreversible. This does not delete copies you previously uploaded to Supabase.") },
        confirmButton = { TextButton(onClick = { confirmErase = false; scope.launch {
            try { dao.erasePoints(); dao.eraseTrips(); dao.eraseCars(); onNotice("All local cars and trips erased") }
            catch (e: Exception) { onNotice(e.localizedMessage ?: "Erase failed") }
        } }) { Text("ERASE") } }, dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("CANCEL") } })
}

@Composable private fun AddCarDialog(ka: Boolean, onDismiss: () -> Unit, onSave: (CarEntity) -> Unit) {
    val context = LocalContext.current
    var nickname by remember { mutableStateOf("") }
    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("2020") }
    var trim by remember { mutableStateOf("") }
    var fuel by remember { mutableStateOf("") }
    var mods by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<String?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            photo = uri.toString()
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add a real car".whenKa(ka, "მანქანის დამატება")) },
        text = {
            Column(Modifier.heightIn(max = 510.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(nickname, { nickname = it }, label = { Text("Nickname *") }, singleLine = true)
                OutlinedTextField(make, { make = it }, label = { Text("Make *") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("Model *") }, singleLine = true)
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Year *") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(trim, { trim = it }, label = { Text("Trim") }, singleLine = true)
                OutlinedTextField(fuel, { fuel = it }, label = { Text("Fuel type") }, singleLine = true)
                OutlinedTextField(mods, { mods = it }, label = { Text("Modifications (owner-entered)") })
                TextButton(onClick = { photos.launch(arrayOf("image/*")) }) { Text(if (photo == null) "+ Select car photo" else "Photo selected") }
                Text("Specs and ownership are self-reported; not verified by GPS.", color = Soft, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(enabled = nickname.isNotBlank() && make.isNotBlank() && model.isNotBlank() && ((year.toIntOrNull() ?: 0) in 1886..2100),
            onClick = { onSave(CarEntity(UUID.randomUUID().toString(), nickname.trim(), make.trim(), model.trim(), year.toInt(), trim.trim(), fuel.trim(), mods.trim(), photo)) }) {
            Text("SAVE", color = Lime)
        } }, dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } })
}

@Composable private fun AuthDialog(api: GridApi, onDismiss: () -> Unit, onNotice: (String) -> Unit, onSuccess: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Your GRID account") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Text("Email confirmation may be required before signing in.", fontSize = 12.sp, color = Soft)
            TextButton(enabled = email.contains("@") && !busy, onClick = { scope.launch {
                busy = true; try { api.resetPassword(email); onNotice("Check your email for the recovery link") }
                catch (e: Exception) { onNotice(e.localizedMessage ?: "Recovery failed") } finally { busy = false }
            } }) { Text("Forgot password?") }
        } },
        confirmButton = { Row {
            TextButton(enabled = !busy && email.contains("@") && password.length >= 6, onClick = { scope.launch {
                busy = true; try { api.signup(email, password); onNotice("Account created. Verify email, then log in.") }
                catch (e: Exception) { onNotice(e.localizedMessage ?: "Sign up failed") } finally { busy = false }
            } }) { Text("SIGN UP") }
            TextButton(enabled = !busy && email.contains("@") && password.isNotEmpty(), onClick = { scope.launch {
                busy = true; try { api.login(email, password); onSuccess() }
                catch (e: Exception) { onNotice(e.localizedMessage ?: "Login failed") } finally { busy = false }
            } }) { Text("LOG IN", color = Lime) }
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } })
}
