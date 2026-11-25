package com.expensetracker.app.trips

import android.content.Context
import com.expensetracker.app.data.ActiveTrip
import com.expensetracker.app.data.Expense
import com.expensetracker.app.data.Trips
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Trip mode: the trip you're on, kept in prefs so quick log, the widget and auto-capture (which
 * don't run inside the app's screens) tag new spends with it too. Ends itself after its end date.
 */
object TripStore {
    private const val PREFS = "trip_mode"
    private val _active = MutableStateFlow<ActiveTrip?>(null)
    @Volatile private var loaded = false

    fun active(ctx: Context): StateFlow<ActiveTrip?> {
        current(ctx)
        return _active
    }

    /** The active trip, or null (also clears one whose end date has passed). */
    fun current(ctx: Context): ActiveTrip? {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                _active.value = p.getString("name", null)?.let { name ->
                    ActiveTrip(
                        name,
                        LocalDate.ofEpochDay(p.getLong("start", LocalDate.now().toEpochDay())),
                        p.getLong("end", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay),
                    )
                }
                loaded = true
            }
        }
        val a = _active.value
        if (a != null && a.isOver(LocalDate.now())) { end(ctx); return null }
        return a
    }

    fun start(ctx: Context, trip: ActiveTrip) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", trip.name)
            .putLong("start", trip.start.toEpochDay())
            .apply { if (trip.end != null) putLong("end", trip.end.toEpochDay()) else remove("end") }
            .apply()
        loaded = true
        _active.value = trip
    }

    fun end(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        loaded = true
        _active.value = null
    }

    /** New entry → tagged with the active trip if trip mode applies to it. */
    fun tag(ctx: Context, e: Expense): Expense = Trips.autoTag(e, current(ctx), LocalDate.now())

    /** Keeps trip mode pointing at the right name after a rename/merge/delete. */
    fun renamed(ctx: Context, from: String, to: String?) {
        val a = current(ctx) ?: return
        if (a.name != from) return
        if (to == null) end(ctx) else start(ctx, a.copy(name = to))
    }
}
