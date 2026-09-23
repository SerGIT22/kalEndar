package com.carnetdegamer.calendario.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset


data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int, val writable: Boolean)
data class CalendarEvent(
    val id: Long,
    val calendarId: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String,
    val description: String,
    val color: Int,
    val rrule: String? = null
)

data class EventDraft(
    val title: String,
    val calendarId: Long,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String,
    val description: String,
    val reminderMinutes: Int,
    val recurrence: String?,
    val attendees: List<String>
)

class CalendarRepository(private val resolver: ContentResolver) {
    fun calendars(): List<CalendarInfo> {
        val result = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection,
            "${CalendarContract.Calendars.VISIBLE}=1", null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC")?.use { c ->
            val id = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val name = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val account = c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val color = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)
            val access = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            while (c.moveToNext()) {
                val level = c.getInt(access)
                result += CalendarInfo(c.getLong(id), c.getString(name), c.getString(account), c.getInt(color), level >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)
            }
        }
        return result
    }

    fun events(from: Long, to: Long): List<CalendarEvent> {
        val result = mutableListOf<CalendarEvent>()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.RRULE
        )
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString()).appendPath(to.toString()).build()
        resolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val iCal = c.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val iTitle = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val iBegin = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val iEnd = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val iAll = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val iLoc = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val iDesc = c.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
            val iColor = c.getColumnIndexOrThrow(CalendarContract.Instances.DISPLAY_COLOR)
            val iRule = c.getColumnIndexOrThrow(CalendarContract.Instances.RRULE)
            while (c.moveToNext()) {
                result += CalendarEvent(
                    c.getLong(iId), c.getLong(iCal), c.getString(iTitle) ?: "Sin título",
                    c.getLong(iBegin), c.getLong(iEnd), c.getInt(iAll) != 0,
                    c.getString(iLoc) ?: "", c.getString(iDesc) ?: "", c.getInt(iColor),
                    c.getString(iRule)
                )
            }
        }
        return result
    }

    fun insert(draft: EventDraft): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, draft.calendarId)
            put(CalendarContract.Events.TITLE, draft.title)
            put(CalendarContract.Events.DESCRIPTION, draft.description)
            put(CalendarContract.Events.EVENT_LOCATION, draft.location)
            put(CalendarContract.Events.DTSTART, draft.startMillis)
            put(CalendarContract.Events.DTEND, draft.endMillis)
            put(CalendarContract.Events.ALL_DAY, if (draft.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else ZoneId.systemDefault().id)
            if (!draft.recurrence.isNullOrBlank()) put(CalendarContract.Events.RRULE, draft.recurrence)
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("No se pudo crear el evento")
        val id = ContentUris.parseId(uri)
        if (draft.reminderMinutes >= 0) {
            resolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id)
                put(CalendarContract.Reminders.MINUTES, draft.reminderMinutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
        draft.attendees.forEach { email ->
            resolver.insert(CalendarContract.Attendees.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Attendees.EVENT_ID, id)
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
            })
        }
        return id
    }

    fun update(eventId: Long, draft: EventDraft) {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, draft.calendarId)
            put(CalendarContract.Events.TITLE, draft.title)
            put(CalendarContract.Events.DESCRIPTION, draft.description)
            put(CalendarContract.Events.EVENT_LOCATION, draft.location)
            put(CalendarContract.Events.DTSTART, draft.startMillis)
            put(CalendarContract.Events.DTEND, draft.endMillis)
            put(CalendarContract.Events.ALL_DAY, if (draft.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (draft.allDay) "UTC" else ZoneId.systemDefault().id)
            put(CalendarContract.Events.RRULE, draft.recurrence)
        }
        resolver.update(uri, values, null, null)
        resolver.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(eventId.toString()))
        if (draft.reminderMinutes >= 0) resolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId); put(CalendarContract.Reminders.MINUTES, draft.reminderMinutes); put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
    }

    fun delete(eventId: Long) {
        resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null)
    }
}

fun LocalDate.atStartMillis(): Long = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun LocalDate.atEndMillis(): Long = plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
fun millisToLocalDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
