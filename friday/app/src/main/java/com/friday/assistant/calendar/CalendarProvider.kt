package com.friday.assistant.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class EventInfo(val title: String, val start: LocalDateTime, val end: LocalDateTime, val allDay: Boolean, val location: String = "")

class CalendarException(message: String) : Exception(message)

interface CalendarProvider {
    fun canRead(): Boolean
    fun canWrite(): Boolean
    /** Events overlapping [from, to), sorted by start. */
    fun events(from: LocalDateTime, to: LocalDateTime): List<EventInfo>
    /** Inserts an event into the first writable calendar. Returns false if there is no writable calendar. */
    fun insert(title: String, start: LocalDateTime, end: LocalDateTime): Boolean
}

/** Android Calendar Provider (READ_CALENDAR / WRITE_CALENDAR). Nothing is cached or copied off the device. */
class AndroidCalendarProvider(private val context: Context, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) : CalendarProvider {
    private fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    override fun canRead() = has(Manifest.permission.READ_CALENDAR)
    override fun canWrite() = has(Manifest.permission.WRITE_CALENDAR)

    override fun events(from: LocalDateTime, to: LocalDateTime): List<EventInfo> {
        if (!canRead()) throw SecurityException("READ_CALENDAR")
        val z = zone()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from.atZone(z).toInstant().toEpochMilli())
        ContentUris.appendId(builder, to.atZone(z).toInstant().toEpochMilli())
        val out = ArrayList<EventInfo>()
        context.contentResolver.query(
            builder.build(),
            arrayOf(
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            ),
            null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(3) == 1
                // All-day events are stored in UTC; read them as the same calendar date locally.
                val zoneOfEvent = if (allDay) ZoneId.of("UTC") else z
                val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(1)), zoneOfEvent)
                val end = LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), zoneOfEvent)
                out += EventInfo(c.getString(0).orEmpty().ifBlank { "(no title)" }, start, end, allDay, c.getString(4).orEmpty())
            }
        }
        return out
    }

    override fun insert(title: String, start: LocalDateTime, end: LocalDateTime): Boolean {
        if (!canWrite()) throw SecurityException("WRITE_CALENDAR")
        val z = zone()
        var calId: Long? = null
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1",
            null, "${CalendarContract.Calendars.IS_PRIMARY} DESC",
        )?.use { if (it.moveToFirst()) calId = it.getLong(0) }
        val id = calId ?: return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, id)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start.atZone(z).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.atZone(z).toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, z.id)
        }
        return context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null
    }
}

fun LocalDate.startOfDay(): LocalDateTime = atStartOfDay()
