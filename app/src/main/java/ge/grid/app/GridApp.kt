package ge.grid.app

import android.app.Application
import androidx.room.Room
import ge.grid.app.data.GridDatabase

class GridApp : Application() {
    val database: GridDatabase by lazy {
        Room.databaseBuilder(applicationContext, GridDatabase::class.java, "grid.db")
            // No destructive fallback: preserve all recorded journeys.
            .build()
    }
}
