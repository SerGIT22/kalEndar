package com.carnetdegamer.calendario

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carnetdegamer.calendario.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CalendarApp() }
    }
}

class CalendarViewModel : ViewModel() {
    private var repo: CalendarRepository? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Job? = null
    private var loadedFrom: LocalDate? = null
    private var loadedTo: LocalDate? = null

    var calendars by mutableStateOf<List<CalendarInfo>>(emptyList()); private set
    var events by mutableStateOf<List<CalendarEvent>>(emptyList()); private set
    var ready by mutableStateOf(false); private set

    // CalendarProvider queries can be slow, especially with Google Calendar.
    // Never execute them on the Compose/UI thread.
    fun attach(r: CalendarRepository) {
        if (repo == null) {
            repo = r
            refresh(LocalDate.now())
        }
    }

    fun refresh(focus: LocalDate = LocalDate.now()) {
        val r = repo ?: return
        refreshJob?.cancel()
        refreshJob = ioScope.launch {
            val from = focus.minusMonths(6).withDayOfMonth(1).atStartMillis()
            val to = focus.plusMonths(6).withDayOfMonth(1).atStartMillis()
            val loadedCalendars = r.calendars()
            val loadedEvents = r.events(from, to)
            withContext(Dispatchers.Main.immediate) {
                calendars = loadedCalendars
                events = loadedEvents
                loadedFrom = from.toLocalDate()
                loadedTo = to.toLocalDate()
                ready = true
            }
        }
    }

    fun ensureRange(focus: LocalDate) {
        // Do not hit CalendarProvider while the user is navigating. Only reload
        // when the selected date actually leaves the cached window.
        val from = loadedFrom
        val to = loadedTo
        if (from == null || to == null || focus.isBefore(from.plusMonths(1)) || focus.isAfter(to.minusMonths(1))) {
            refresh(focus)
        }
    }

    fun create(draft: EventDraft) {
        val r = repo ?: return
        ioScope.launch {
            r.insert(draft)
            refresh(draft.startMillis.toLocalDate())
        }
    }

    fun update(id: Long, draft: EventDraft) {
        val r = repo ?: return
        ioScope.launch {
            r.update(id, draft)
            refresh(draft.startMillis.toLocalDate())
        }
    }

    fun delete(id: Long) {
        val r = repo ?: return
        ioScope.launch {
            r.delete(id)
            refresh()
        }
    }

    override fun onCleared() {
        ioScope.cancel()
        super.onCleared()
    }
}

private fun Long.toLocalDate(): LocalDate = millisToLocalDate(this)

enum class CalendarView { MONTH, WEEK, DAY, AGENDA }

private val esLocale = Locale("es", "ES")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarApp(vm: CalendarViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermission = result[Manifest.permission.READ_CALENDAR] == true
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission) vm.attach(CalendarRepository(context.contentResolver))
    }

    MaterialTheme(colorScheme = expressiveScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!hasPermission) PermissionScreen {
                launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            } else CalendarScreen(vm)
        }
    }
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    val view = LocalView.current
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(116.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.CalendarMonth, null, tint = Color.White, modifier = Modifier.size(60.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("Calendario", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Conecta tus calendarios del teléfono, incluido Google Calendar cuando esté sincronizado.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onGrant() },
            shape = RoundedCornerShape(22.dp)
        ) { Text("Conceder acceso") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: CalendarViewModel) {
    var view by remember { mutableStateOf(CalendarView.MONTH) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var showCreator by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    val haptic = LocalView.current

    val title = when (view) {
        CalendarView.MONTH -> date.month.getDisplayName(TextStyle.FULL, esLocale).replaceFirstChar { it.uppercase(esLocale) }
        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, esLocale).replaceFirstChar { it.uppercase(esLocale) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            FloatingBottomBar(
                view = view,
                onView = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    view = it
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${date.dayOfMonth} de ${date.month.getDisplayName(TextStyle.FULL, esLocale)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    date = LocalDate.now(); vm.refresh(date)
                }) { Icon(Icons.Rounded.Today, "Hoy") }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    vm.refresh(date)
                }) { Icon(Icons.Rounded.Sync, "Sincronizar") }
            }
            Spacer(Modifier.height(10.dp))
            ViewSwitcher(
                view,
                date,
                onDate = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    date = it
                    vm.ensureRange(it)
                },
                vm.events,
                onEvent = { editing = it; showCreator = true }
            )
        }
    }

    // Separate FAB: deliberately outside the bottom navigation, matching the requested Pixel/Material Expressive layout.
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(end = 18.dp, bottom = 92.dp), contentAlignment = Alignment.BottomEnd) {
        FloatingActionButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                editing = null
                showCreator = true
            },
            modifier = Modifier.size(58.dp),
            shape = RoundedCornerShape(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 5.dp, pressedElevation = 8.dp)
        ) { Icon(Icons.Rounded.Add, "Nuevo evento", modifier = Modifier.size(28.dp)) }
    }

    if (showCreator) {
        EventEditorDialog(
            vm,
            date,
            editing,
            onDismiss = { showCreator = false },
            onSaved = { showCreator = false }
        )
    }
}

@Composable
fun ViewSwitcher(
    view: CalendarView,
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
    events: List<CalendarEvent>,
    onEvent: (CalendarEvent) -> Unit
) {
    AnimatedContent(
        targetState = view,
        transitionSpec = {
            (fadeIn(animationSpec = tween(180)) + scaleIn(initialScale = 0.985f, animationSpec = tween(180))) togetherWith
                (fadeOut(animationSpec = tween(120)) + scaleOut(targetScale = 1.015f, animationSpec = tween(120)))
        },
        label = "view"
    ) { v ->
        when (v) {
            CalendarView.MONTH -> MonthView(date, events, onDate, onEvent)
            CalendarView.WEEK -> WeekView(date, events, onDate, onEvent)
            CalendarView.DAY -> DayView(date, events, onEvent)
            CalendarView.AGENDA -> AgendaView(date, events, onEvent)
        }
    }
}

@Composable
fun MonthView(date: LocalDate, events: List<CalendarEvent>, onDate: (LocalDate) -> Unit, onEvent: (CalendarEvent) -> Unit) {
    var month by remember(date.year, date.month) { mutableStateOf(date.withDayOfMonth(1)) }
    val haptic = LocalView.current
    val eventsByDate = remember(events) { events.groupBy { millisToLocalDate(it.start) } }

    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                var dragTotal = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount -> dragTotal += dragAmount },
                    onDragEnd = {
                        when {
                            dragTotal > 80f -> {
                                haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                month = month.minusMonths(1); onDate(month)
                            }
                            dragTotal < -80f -> {
                                haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                month = month.plusMonths(1); onDate(month)
                            }
                        }
                        dragTotal = 0f
                    },
                    onDragCancel = { dragTotal = 0f }
                )
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                month = month.minusMonths(1); onDate(month)
            }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Mes anterior") }
            Text(
                "${month.month.getDisplayName(TextStyle.FULL, esLocale).replaceFirstChar { it.uppercase(esLocale) }} ${month.year}",
                Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center
            )
            IconButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                month = month.plusMonths(1); onDate(month)
            }) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Mes siguiente") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("L", "M", "X", "J", "V", "S", "D").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(4.dp))
        AnimatedContent(
            targetState = month,
            transitionSpec = {
                (fadeIn(tween(160)) + scaleIn(initialScale = 0.985f, animationSpec = tween(160))) togetherWith
                    (fadeOut(tween(100)) + scaleOut(targetScale = 1.01f, animationSpec = tween(100)))
            },
            label = "monthGrid"
        ) { targetMonth ->
            val targetFirst = targetMonth.withDayOfMonth(1)
            val targetOffset = targetFirst.dayOfWeek.value - 1
            val targetDays = (0 until targetOffset).map { targetFirst.minusDays((targetOffset - it).toLong()) } +
                (1..targetMonth.lengthOfMonth()).map { targetMonth.withDayOfMonth(it) }
            val targetPadded = targetDays + (0 until ((7 - targetDays.size % 7) % 7)).map { targetDays.last().plusDays((it + 1).toLong()) }

            LazyVerticalGrid(
                columns = GridCells.Fixed(7),
                modifier = Modifier.heightIn(max = 372.dp),
                userScrollEnabled = false
            ) {
                items(
                    items = targetPadded,
                    key = { it.toEpochDay() }
                ) { day ->
                    val selected = day == date
                    val dayEvents = eventsByDate[day].orEmpty()
                    Column(
                        Modifier
                            .padding(2.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onDate(day)
                            }
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            day.dayOfMonth.toString(),
                            color = if (day.month != targetMonth.month) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        Row(Modifier.height(10.dp), horizontalArrangement = Arrangement.Center) {
                            dayEvents.take(3).forEach { event ->
                                Box(Modifier.padding(horizontal = 1.dp).size(5.dp).clip(CircleShape).background(Color(event.color)))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${date.dayOfWeek.getDisplayName(TextStyle.FULL, esLocale).replaceFirstChar { it.uppercase(esLocale) }} ${date.dayOfMonth}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        EventList(events.filter { millisToLocalDate(it.start) == date }, onEvent)
    }
}

@Composable
fun WeekView(date: LocalDate, events: List<CalendarEvent>, onDate: (LocalDate) -> Unit, onEvent: (CalendarEvent) -> Unit) {
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    val haptic = LocalView.current
    val eventsByDate = remember(events) { events.groupBy { millisToLocalDate(it.start) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (0..6).forEach { i ->
            val d = monday.plusDays(i.toLong())
            val selected = d == date
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onDate(d) }
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, esLocale).take(2), fontSize = 11.sp)
                Text(d.dayOfMonth.toString(), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Row { eventsByDate[d].orEmpty().take(3).forEach { Box(Modifier.padding(horizontal = 1.dp).size(5.dp).clip(CircleShape).background(Color(it.color))) } }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    EventList((0..6).flatMap { eventsByDate[monday.plusDays(it.toLong())].orEmpty() }.sortedBy { it.start }, onEvent)
}

@Composable
fun DayView(date: LocalDate, events: List<CalendarEvent>, onEvent: (CalendarEvent) -> Unit) {
    val dayEvents = events.filter { millisToLocalDate(it.start) == date }.sortedBy { it.start }
    val allDay = dayEvents.filter { it.allDay }
    val timed = dayEvents.filterNot { it.allDay }
    val zone = ZoneId.systemDefault()
    val eventsByHour = remember(timed) { timed.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).hour } }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        if (allDay.isNotEmpty()) {
            item {
                Text("Todo el día", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, bottom = 7.dp))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                    allDay.forEach { EventCard(it, onEvent) }
                }
            }
        }
        items(items = (0..23).toList(), key = { it }) { hour ->
            val hourEvents = eventsByHour[hour].orEmpty()
            Row(
                Modifier.fillMaxWidth().heightIn(min = if (hourEvents.isEmpty()) 52.dp else 74.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    String.format("%02d:00", hour),
                    Modifier.width(54.dp).padding(top = 5.dp),
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 12.sp
                )
                Column(
                    Modifier.weight(1f).padding(start = 8.dp, end = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    hourEvents.forEach { EventCard(it, onEvent) }
                }
            }
        }
    }
}

@Composable
fun AgendaView(date: LocalDate, events: List<CalendarEvent>, onEvent: (CalendarEvent) -> Unit) {
    val sorted = remember(events, date) {
        events
            .filter { !millisToLocalDate(it.start).isBefore(date.minusDays(7)) && !millisToLocalDate(it.start).isAfter(date.plusDays(30)) }
            .sortedBy { it.start }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        items(items = sorted, key = { it.id }) { EventCard(it, onEvent) }
    }
}

@Composable
fun EventList(events: List<CalendarEvent>, onEvent: (CalendarEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { events.forEach { EventCard(it, onEvent) } }
}

@Composable
fun EventCard(e: CalendarEvent, onEvent: (CalendarEvent) -> Unit) {
    val haptic = LocalView.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onEvent(e) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).height(42.dp).clip(RoundedCornerShape(4.dp)).background(Color(e.color)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, fontWeight = FontWeight.SemiBold)
            Text(
                if (e.allDay) "Todo el día" else timeText(e.start) + " – " + timeText(e.end),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            if (e.location.isNotBlank()) Text(e.location, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

fun timeText(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
fun FloatingBottomBar(view: CalendarView, onView: (CalendarView) -> Unit) {
    val haptic = LocalView.current
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavItem("Hoy", Icons.Rounded.Today, view == CalendarView.DAY) { haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onView(CalendarView.DAY) }
            NavItem("Agenda", Icons.Rounded.ViewAgenda, view == CalendarView.AGENDA) { haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onView(CalendarView.AGENDA) }
            NavItem("Mes", Icons.Rounded.CalendarMonth, view == CalendarView.MONTH) { haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onView(CalendarView.MONTH) }
            NavItem("Semana", Icons.Rounded.ViewWeek, view == CalendarView.WEEK) { haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); onView(CalendarView.WEEK) }
        }
    }
}

@Composable
fun RowScope.NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).clickable { onClick() }.padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, fontSize = 10.sp, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorDialog(
    vm: CalendarViewModel,
    selectedDate: LocalDate,
    editing: CalendarEvent?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var title by remember(editing) { mutableStateOf(editing?.title ?: "") }
    var location by remember(editing) { mutableStateOf(editing?.location ?: "") }
    var description by remember(editing) { mutableStateOf(editing?.description ?: "") }
    var allDay by remember(editing) { mutableStateOf(editing?.allDay ?: false) }
    var calendarId by remember(editing, vm.calendars) {
        mutableStateOf(editing?.calendarId ?: vm.calendars.firstOrNull { it.writable }?.id ?: vm.calendars.firstOrNull()?.id ?: -1L)
    }
    var startDate by remember(editing) { mutableStateOf(editing?.let { millisToLocalDate(it.start) } ?: selectedDate) }
    var startHour by remember(editing) { mutableStateOf(editing?.let { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).hour } ?: 10) }
    var startMinute by remember(editing) { mutableStateOf(editing?.let { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).minute } ?: 0) }
    var duration by remember(editing) { mutableStateOf(if (editing != null) ((editing.end - editing.start) / 60_000L).toInt().coerceAtLeast(1) else 60) }
    var reminder by remember(editing) { mutableStateOf(10) }
    var recurrence by remember(editing) { mutableStateOf(editing?.rrule ?: "") }
    var attendees by remember(editing) { mutableStateOf("") }
    var calendarMenu by remember { mutableStateOf(false) }
    val haptic = LocalView.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).safeDrawingPadding().padding(horizontal = 12.dp),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (editing == null) "Nuevo evento" else "Editar evento", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Calendario", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cerrar") }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
                    }
                    item {
                        Box {
                            OutlinedButton(onClick = { calendarMenu = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                                Text(vm.calendars.firstOrNull { it.id == calendarId }?.let { "${it.name} · ${it.account}" } ?: "Calendario", Modifier.weight(1f), textAlign = TextAlign.Start)
                                Icon(Icons.Rounded.ExpandMore, null)
                            }
                            DropdownMenu(expanded = calendarMenu, onDismissRequest = { calendarMenu = false }) {
                                vm.calendars.filter { it.writable }.forEach {
                                    DropdownMenuItem(text = { Text(it.name) }, onClick = { calendarId = it.id; calendarMenu = false })
                                }
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Todo el día", fontWeight = FontWeight.Medium)
                                Text("Sin hora de inicio ni fin", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Switch(checked = allDay, onCheckedChange = { haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); allDay = it })
                        }
                    }
                    item {
                        OutlinedTextField(startDate.toString(), { runCatching { startDate = LocalDate.parse(it) } }, label = { Text("Fecha (AAAA-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
                    }
                    if (!allDay) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(startHour.toString().padStart(2, '0'), { startHour = it.toIntOrNull()?.coerceIn(0, 23) ?: startHour }, label = { Text("Hora") }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp))
                                OutlinedTextField(startMinute.toString().padStart(2, '0'), { startMinute = it.toIntOrNull()?.coerceIn(0, 59) ?: startMinute }, label = { Text("Min") }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp))
                                OutlinedTextField(duration.toString(), { duration = it.toIntOrNull()?.coerceAtLeast(1) ?: duration }, label = { Text("Duración") }, singleLine = true, modifier = Modifier.weight(1.25f), shape = RoundedCornerShape(18.dp))
                            }
                        }
                    }
                    item { OutlinedTextField(location, { location = it }, label = { Text("Ubicación") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) }
                    item { OutlinedTextField(description, { description = it }, label = { Text("Descripción") }, minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) }
                    item { OutlinedTextField(attendees, { attendees = it }, label = { Text("Invitados · emails separados por coma") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) }
                    item { OutlinedTextField(reminder.toString(), { reminder = it.toIntOrNull()?.coerceAtLeast(0) ?: reminder }, label = { Text("Recordatorio · minutos antes") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) }
                    item { OutlinedTextField(recurrence, { recurrence = it }, label = { Text("Repetición · RRULE opcional") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (editing != null) {
                        TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); vm.delete(editing.id); onDismiss() }) { Text("Eliminar") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            val zone = ZoneId.systemDefault()
                            val start = if (allDay) startDate.atStartMillis() else startDate.atTime(startHour, startMinute).atZone(zone).toInstant().toEpochMilli()
                            val end = if (allDay) startDate.atEndMillis() else start + duration * 60_000L
                            val draft = EventDraft(
                                title.ifBlank { "Sin título" }, calendarId, start, end, allDay, location, description,
                                reminder, recurrence.ifBlank { null }, attendees.split(",").map { it.trim() }.filter { it.contains("@") }
                            )
                            if (editing == null) vm.create(draft) else vm.update(editing.id, draft)
                            onSaved()
                        },
                        enabled = title.isNotBlank() && calendarId >= 0 && vm.calendars.any { it.id == calendarId && it.writable },
                        shape = RoundedCornerShape(18.dp)
                    ) { Text(if (editing == null) "Crear" else "Guardar") }
                }
            }
        }
    }
}

fun expressiveScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF246BFE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondaryContainer = Color(0xFFE0E2EC),
    surface = Color(0xFFF9F9FF),
    surfaceContainer = Color(0xFFEFEFF6),
    surfaceContainerHigh = Color(0xFFE8E8F0),
    surfaceContainerLow = Color(0xFFF3F3FA),
    background = Color(0xFFF9F9FF)
)
