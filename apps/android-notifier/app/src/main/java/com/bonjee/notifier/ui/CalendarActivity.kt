package com.bonjee.notifier.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bonjee.notifier.R
import com.bonjee.notifier.databinding.ActivityCalendarBinding
import com.bonjee.notifier.databinding.ItemEventBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Native calendar list.
 *
 * Reads upcoming instances straight from the device provider, so recurring
 * events appear on their real dates. It is read-only: "NEW EVENT" opens the
 * system calendar's insert screen, where the user confirms the event. The app
 * never writes an event silently.
 */
class CalendarActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalendarBinding

    private data class DeviceEvent(
        val id: Long,
        val title: String,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val location: String,
    )

    private var events: List<DeviceEvent> = emptyList()
    private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fullFormat = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Calendar permission denied. You can still create an event, but the list stays empty.",
                Toast.LENGTH_LONG,
            ).show()
        }
        refresh()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = events.size
        override fun getItem(position: Int): Any = events[position]
        override fun getItemId(position: Int): Long = events[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = if (convertView == null) {
                ItemEventBinding.inflate(layoutInflater, parent, false)
            } else {
                ItemEventBinding.bind(convertView)
            }
            val event = events[position]
            row.txtEventTitle.text = event.title.ifBlank { "(untitled event)" }
            row.txtEventWhen.text = whenText(event)
            row.txtEventWhere.text = event.location.ifBlank { "" }
            row.txtEventWhere.visibility = if (event.location.isBlank()) View.GONE else View.VISIBLE
            return row.root
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.listEvents.adapter = adapter
        binding.btnCalendarRefresh.setOnClickListener { refresh() }
        binding.btnCalendarPermission.setOnClickListener {
            permissionLauncher.launch(Manifest.permission.READ_CALENDAR)
        }
        binding.btnNewEvent.setOnClickListener { newEvent() }

        binding.txtCalendarFooter.text =
            "JARVIS only reads the calendar. Creating an event opens your calendar app, so the " +
                "final save is always yours."
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

        binding.btnCalendarPermission.isEnabled = !granted
        binding.btnCalendarPermission.text = if (granted) "GRANTED" else "ALLOW"

        if (!granted) {
            events = emptyList()
            adapter.notifyDataSetChanged()
            binding.txtCalendarStatus.text =
                "Calendar permission: NOT granted\nTap ALLOW to read upcoming events.\n" +
                    "Creating an event still works through the calendar app."
            return
        }

        events = try {
            readEvents()
        } catch (t: Throwable) {
            binding.txtCalendarStatus.text =
                "Could not read the calendar (${t.javaClass.simpleName}). No events were changed."
            emptyList()
        }
        adapter.notifyDataSetChanged()

        val now = System.currentTimeMillis()
        val next = events.firstOrNull { it.end >= now }
        binding.txtCalendarStatus.text = buildString {
            appendLine("Calendar permission: granted")
            appendLine("upcoming ${events.size} in the next 60 days")
            if (next != null) {
                append("next: ").append(next.title.ifBlank { "(untitled)" })
                    .append(" · ").append(whenText(next))
            } else {
                append("Nothing scheduled in the next 60 days.")
            }
        }
        if (events.isEmpty()) {
            binding.txtCalendarStatus.append("\nNo events found. Tap NEW EVENT to add one.")
        }
    }

    private fun readEvents(): List<DeviceEvent> {
        val now = System.currentTimeMillis()
        val begin = now - 12L * 60L * 60L * 1000L
        val end = now + 60L * 24L * 60L * 60L * 1000L

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(begin.toString())
            .appendPath(end.toString())
            .build()

        val projection = arrayOf(
            CalendarContract.Instances._ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )

        val out = ArrayList<DeviceEvent>()
        contentResolver.query(
            uri,
            projection,
            "${CalendarContract.Instances.END} >= ?",
            arrayOf(now.toString()),
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances._ID)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val locationIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)

            while (cursor.moveToNext() && out.size < 150) {
                out.add(
                    DeviceEvent(
                        id = cursor.getLong(idIndex),
                        title = cursor.getString(titleIndex).orEmpty(),
                        begin = cursor.getLong(beginIndex),
                        end = cursor.getLong(endIndex),
                        allDay = cursor.getInt(allDayIndex) == 1,
                        location = cursor.getString(locationIndex).orEmpty(),
                    ),
                )
            }
        }
        return out
    }

    private fun whenText(event: DeviceEvent): String {
        if (event.begin <= 0) return "time unknown"
        return if (event.allDay) {
            dayFormat.format(Date(event.begin)) + " · all day"
        } else {
            val start = Calendar.getInstance().apply { timeInMillis = event.begin }
            val finish = Calendar.getInstance().apply { timeInMillis = event.end }
            val sameDay = start.get(Calendar.YEAR) == finish.get(Calendar.YEAR) &&
                start.get(Calendar.DAY_OF_YEAR) == finish.get(Calendar.DAY_OF_YEAR)
            if (sameDay) {
                dayFormat.format(Date(event.begin)) + " · " +
                    timeFormat.format(Date(event.begin)) + "–" + timeFormat.format(Date(event.end))
            } else {
                fullFormat.format(Date(event.begin)) + " – " + fullFormat.format(Date(event.end))
            }
        }
    }

    private fun newEvent() {
        val start = System.currentTimeMillis() + 60L * 60L * 1000L
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 60L * 60L * 1000L)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(
                this,
                "No calendar app handled the new-event screen.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
