package com.jarvis.assistant.calendar

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CalendarReader(private val context: Context) {
    fun hasPermission() = Perms.hasCalendar(context)

    /** Events overlapping [startMs, endMs), oldest first, as (begin time, title). Empty without permission. */
    suspend fun between(startMs: Long, endMs: Long): List<Pair<Long, String>> {
        if (!hasPermission()) return emptyList()
        return withContext(Dispatchers.IO) {
            val out = mutableListOf<Pair<Long, String>>()
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, startMs)
            ContentUris.appendId(builder, endMs)
            context.contentResolver.query(
                builder.build(),
                arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.TITLE),
                null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) out += c.getLong(0) to (c.getString(1) ?: "(no title)")
            }
            out
        }
    }
}
