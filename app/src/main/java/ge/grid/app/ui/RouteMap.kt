package ge.grid.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ge.grid.app.BuildConfig
import ge.grid.app.data.PointEntity
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView

@Composable
fun RouteMap(points: List<PointEntity>) {
    Column {
        val key = BuildConfig.MAPTILER_KEY
        if (key.isBlank()) RouteTrace(points)
        else {
            val context = LocalContext.current
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val view = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(null) } }
            DisposableEffect(view, lifecycle) {
                val watcher = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> view.onStart()
                        Lifecycle.Event.ON_RESUME -> view.onResume()
                        Lifecycle.Event.ON_PAUSE -> view.onPause()
                        Lifecycle.Event.ON_STOP -> view.onStop()
                        else -> Unit
                    }
                }
                lifecycle.addObserver(watcher)
                onDispose { lifecycle.removeObserver(watcher); view.onDestroy() }
            }
            AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(260.dp), update = { mapView ->
                mapView.getMapAsync { map ->
                    map.setStyle("https://api.maptiler.com/maps/streets-v4/style.json?key=$key") {
                        val coords = points.map { LatLng(it.latitude, it.longitude) }
                        if (coords.isNotEmpty()) {
                            if (coords.size > 1) map.addPolyline(PolylineOptions().addAll(coords).color(0xFFCCFA69.toInt()).width(4f))
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(coords[coords.size / 2], 12.5))
                        }
                    }
                }
            })
            Text("Map tiles © MapTiler · © OpenStreetMap contributors. Map rendering needs network.", color = Color.Gray, fontSize = 10.sp)
        }
    }
}

/** Always works offline; expressly a geometric trace, not a map or street geography. */
@Composable private fun RouteTrace(points: List<PointEntity>) {
    Column {
        Canvas(Modifier.fillMaxWidth().height(236.dp).background(Color(0xFF1D2520), RoundedCornerShape(14.dp))) {
            if (points.size < 2) return@Canvas
            val minX = points.minOf { it.longitude }; val maxX = points.maxOf { it.longitude }
            val minY = points.minOf { it.latitude }; val maxY = points.maxOf { it.latitude }
            val spanX = (maxX - minX).coerceAtLeast(0.0001)
            val spanY = (maxY - minY).coerceAtLeast(0.0001)
            val mapX: (Double) -> Float = { ((it - minX) / spanX * (size.width * 0.82) + size.width * 0.09).toFloat() }
            val mapY: (Double) -> Float = { ((maxY - it) / spanY * (size.height * 0.82) + size.height * 0.09).toFloat() }
            val path = Path().apply {
                moveTo(mapX(points[0].longitude), mapY(points[0].latitude))
                points.drop(1).forEach { lineTo(mapX(it.longitude), mapY(it.latitude)) }
            }
            drawPath(path, Color(0xFFCCFA69), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
            val first = Offset(mapX(points.first().longitude), mapY(points.first().latitude))
            val last = Offset(mapX(points.last().longitude), mapY(points.last().latitude))
            drawCircle(Color.White, 7.dp.toPx(), first)
            drawCircle(Color(0xFFCCFA69), 7.dp.toPx(), last)
        }
        Spacer(Modifier.height(8.dp))
        Text("OFFLINE ROUTE TRACE · No street map until a licensed map provider key is configured", color = Color.LightGray, fontSize = 10.sp)
    }
}
