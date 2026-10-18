package ge.grid.app.network

import ge.grid.app.BuildConfig
import ge.grid.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Only the publishable Supabase anon key is ever bundled. RLS + RPC enforce server permissions. */
class GridApi {
    val configured = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    private val client = OkHttpClient.Builder().build()
    private var token: String? = null
    private var userId: String? = null
    val signedIn get() = token != null

    private suspend fun request(path: String, method: String = "GET", body: JSONObject? = null, authenticated: Boolean = true): String = withContext(Dispatchers.IO) {
        require(configured) { "Supabase not configured" }
        val req = Request.Builder().url(BuildConfig.SUPABASE_URL.trimEnd('/') + path)
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer ${if (authenticated) token ?: BuildConfig.SUPABASE_ANON_KEY else BuildConfig.SUPABASE_ANON_KEY}")
            .header("Content-Type", "application/json")
            .method(method, if (method == "GET") null else RequestBody.create(MediaType.parse("application/json"), (body ?: JSONObject()).toString()))
            .build()
        client.newCall(req).execute().use { response ->
            val output = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Server ${response.code}: ${output.take(250)}")
            output
        }
    }

    suspend fun login(email: String, password: String) {
        val res = JSONObject(request("/auth/v1/token?grant_type=password", "POST", JSONObject().put("email", email).put("password", password), false))
        token = res.getString("access_token")
        userId = res.getJSONObject("user").getString("id")
    }
    suspend fun signup(email: String, password: String) {
        request("/auth/v1/signup", "POST", JSONObject().put("email", email).put("password", password), false)
    }
    suspend fun resetPassword(email: String) {
        request("/auth/v1/recover", "POST", JSONObject().put("email", email), false)
    }
    fun logout() { token = null; userId = null }

    suspend fun rankingOptIn(): Boolean {
        require(signedIn)
        val id = requireNotNull(userId)
        val data = JSONArray(request("/rest/v1/profiles?select=ranking_opt_in&id=eq.$id"))
        return data.length() > 0 && data.getJSONObject(0).optBoolean("ranking_opt_in")
    }
    suspend fun setRankingOptIn(enabled: Boolean) {
        require(signedIn)
        val id = requireNotNull(userId)
        request("/rest/v1/profiles?id=eq.$id", "PATCH", JSONObject().put("ranking_opt_in", enabled))
    }

    /** JSON points are recomputed on server. UUID is a unique idempotency key. */
    suspend fun uploadTrip(trip: TripEntity, points: List<PointEntity>): String {
        require(signedIn) { "Sign in to sync" }
        val samples = JSONArray()
        points.forEach { p -> samples.put(JSONObject()
            .put("t", p.timestampMs).put("lat", p.latitude).put("lon", p.longitude)
            .put("accuracy", p.accuracyMeters)
            .put("speed", p.gpsSpeedMps?.toDouble() ?: JSONObject.NULL)) }
        val request = JSONObject().put("p_trip_id", trip.id)
            .put("p_started_ms", trip.startedAt).put("p_ended_ms", trip.endedAt)
            .put("p_samples", samples)
        return JSONObject(this.request("/rest/v1/rpc/submit_trip", "POST", request)).optString("status", "submitted")
    }

    data class Standing(val position: Int, val displayName: String, val kilometres: Double)
    suspend fun rankings(period: String): List<Standing> {
        if (!signedIn) return emptyList()
        val json = JSONArray(request("/rest/v1/rpc/distance_leaderboard", "POST",
            JSONObject().put("p_period", period).put("p_limit", 50).put("p_offset", 0)))
        return (0 until json.length()).map { idx ->
            val row = json.getJSONObject(idx)
            Standing(row.getInt("rank"), row.optString("display_name", "Driver"), row.getDouble("km"))
        }
    }
}
