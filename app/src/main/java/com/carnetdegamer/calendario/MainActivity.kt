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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.Spring
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
            refresh(millisToLocalDate(draft.startMillis, draft.allDay))
        }
    }

    fun update(id: Long, draft: EventDraft) {
        val r = repo ?: return
        ioScope.launch {
            r.update(id, draft)
            refresh(millisToLocalDate(draft.startMillis, draft.allDay))
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

private fun eventLocalDate(event: CalendarEvent): LocalDate =
    millisToLocalDate(event.start, event.allDay)

private fun LocalDate.toDatePickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

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

    val darkTheme = isSystemInDarkTheme()
    val colorScheme = remember(darkTheme) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            expressiveScheme(darkTheme)
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
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
                    AnimatedContent(
                        targetState = title,
                        transitionSpec = {
                            (slideInVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(140))) togetherWith
                                (slideOutVertically(animationSpec = tween(140)) { -it / 3 } + fadeOut(tween(100)))
                        },
                        label = "calendar_title"
                    ) { animatedTitle ->
                        Text(animatedTitle, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    }
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
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(end = 16.dp, bottom = 106.dp), contentAlignment = Alignment.BottomEnd) {
        FloatingActionButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                editing = null
                showCreator = true
            },
            modifier = Modifier.size(52.dp),
            shape = RoundedCornerShape(17.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 5.dp, pressedElevation = 8.dp)
        ) { Icon(Icons.Rounded.Add, "Nuevo evento", modifier = Modifier.size(24.dp)) }
    }

    if (showCreator) {
        EventEditorDialog(
            vm,
            date,
            editing,
            onDismiss = { showCreator = false },
            onSaved = { }
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
    // Keep view switching synchronous. AnimatedContent composes both the old
    // and new screen at the same time, which is especially expensive for the
    // month grid. The calendar should feel immediate rather than animated at
    // the cost of dropped frames.
    when (view) {
        CalendarView.MONTH -> MonthView(date, events, onDate, onEvent)
        CalendarView.WEEK -> WeekView(date, events, onDate, onEvent)
        CalendarView.DAY -> DayView(date, events, onEvent)
        CalendarView.AGENDA -> AgendaView(date, events, onEvent)
    }
}

@Composable
fun MonthView(
    date: LocalDate,
    events: List<CalendarEvent>,
    onDate: (LocalDate) -> Unit,
    onEvent: (CalendarEvent) -> Unit
) {
    var month by remember(date.year, date.month) { mutableStateOf(date.withDayOfMonth(1)) }
    val haptic = LocalView.current

    // Pre-calculate the 42 cells only when the month changes. There is no
    // AnimatedContent here: it used to keep two complete month grids alive
    // during the transition and was the main source of stutter on this screen.
    val days = remember(month) {
        val first = month.withDayOfMonth(1)
        val leading = first.dayOfWeek.value - 1
        buildList {
            repeat(leading) { index ->
                add(first.minusDays((leading - index).toLong()))
            }
            for (day in 1..month.lengthOfMonth()) {
                add(month.withDayOfMonth(day))
            }
            while (size < 42) add(last().plusDays(1))
        }
    }

    // Store only the information the grid needs instead of repeatedly
    // filtering the complete event objects for every cell.
    val eventDots = remember(events) {
        events.groupBy { eventLocalDate(it) }
            .mapValues { (_, dayEvents) -> dayEvents.take(3).map { it.color } }
    }

    val selectedDayEvents = remember(events, date) {
        events.filter { eventLocalDate(it) == date }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                var dragTotal = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { _, dragAmount -> dragTotal += dragAmount },
                    onDragEnd = {
                        when {
                            dragTotal > 70f -> {
                                haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                month = month.minusMonths(1)
                                onDate(month)
                            }
                            dragTotal < -70f -> {
                                haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                month = month.plusMonths(1)
                                onDate(month)
                            }
                        }
                        dragTotal = 0f
                    },
                    onDragCancel = { dragTotal = 0f }
                )
            }
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    month = month.minusMonths(1)
                    onDate(month)
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Mes anterior", modifier = Modifier.size(20.dp))
            }

            Text(
                "${month.month.getDisplayName(TextStyle.FULL, esLocale).replaceFirstChar { it.uppercase(esLocale) }} ${month.year}",
                Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                textAlign = TextAlign.Center
            )

            IconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    month = month.plusMonths(1)
                    onDate(month)
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Mes siguiente", modifier = Modifier.size(20.dp))
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 3.dp)) {
            listOf("L", "M", "X", "J", "V", "S", "D").forEach { dayName ->
                Text(
                    dayName,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
            }
        }

        // Fixed 6 x 7 grid. No lazy layout and no animated double-buffering:
        // 42 lightweight cells are cheaper and much more stable here.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            days.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().height(45.dp)) {
                    week.forEach { day ->
                        val selected = day == date
                        val colors = eventDots[day].orEmpty()

                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(horizontal = 1.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    onDate(day)
                                }
                                .padding(top = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                day.dayOfMonth.toString(),
                                color = when {
                                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    day.month != month.month -> MaterialTheme.colorScheme.outline
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )

                            Row(
                                Modifier.height(8.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                colors.forEach { color ->
                                    Box(
                                        Modifier
                                            .padding(horizontal = 1.dp)
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(Color(color))
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            "${date.dayOfWeek.getDisplayName(TextStyle.FULL, esLocale).replaceFirstChar { it.uppercase(esLocale) }} ${date.dayOfMonth}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(5.dp))
        EventList(selectedDayEvents, onEvent)
    }
}

@Composable
fun WeekView(date: LocalDate, events: List<CalendarEvent>, onDate: (LocalDate) -> Unit, onEvent: (CalendarEvent) -> Unit) {
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    val haptic = LocalView.current
    val eventsByDate = remember(events) { events.groupBy { eventLocalDate(it) } }
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
    val dayEvents = events.filter { eventLocalDate(it) == date }.sortedBy { it.start }
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
            .filter { !eventLocalDate(it).isBefore(date.minusDays(7)) && !eventLocalDate(it).isAfter(date.plusDays(30)) }
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
    val items = listOf(
        CalendarView.DAY to ("Hoy" to Icons.Rounded.Today),
        CalendarView.AGENDA to ("Agenda" to Icons.Rounded.ViewAgenda),
        CalendarView.MONTH to ("Mes" to Icons.Rounded.CalendarMonth),
        CalendarView.WEEK to ("Semana" to Icons.Rounded.ViewWeek)
    )
    val selectedIndex = items.indexOfFirst { it.first == view }.coerceAtLeast(0)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        val slotWidth = maxWidth / 4f
        // Keep the indicator a fixed size. Animating both its position and width
        // at the same time made Compose relayout the whole bar and produced the
        // visible halo/judder on slower frames.
        val indicatorWidth = 80.dp
        val targetOffset = slotWidth * selectedIndex + (slotWidth - indicatorWidth) / 2f
        val animatedOffset by androidx.compose.animation.core.animateDpAsState(
            targetValue = targetOffset,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            label = "nav_indicator_offset"
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            shape = RoundedCornerShape(32.dp)
        ) {
            Box(Modifier.fillMaxSize()) {
                // One flat capsule. No elevation/shadow: the outer glow in v10
                // came from the elevated Surface being animated underneath it.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = animatedOffset)
                        .width(indicatorWidth)
                        .height(48.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                )

                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { index, (destination, data) ->
                        val (label, icon) = data
                        val selected = index == selectedIndex
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(26.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    onView(destination)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val iconColor = if (selected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = label,
                                    tint = iconColor,
                                    modifier = Modifier.size(25.dp)
                                )
                                AnimatedVisibility(
                                    visible = selected,
                                    enter = fadeIn(tween(170)) + slideInVertically(tween(190)) { it / 3 },
                                    exit = fadeOut(tween(100)) + slideOutVertically(tween(120)) { -it / 3 }
                                ) {
                                    Row {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            label,
                                            fontSize = 12.sp,
                                            lineHeight = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = iconColor,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
    var startDate by remember(editing) { mutableStateOf(editing?.let { eventLocalDate(it) } ?: selectedDate) }
    var startHour by remember(editing) { mutableStateOf(editing?.let { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).hour } ?: 10) }
    var startMinute by remember(editing) { mutableStateOf(editing?.let { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).minute } ?: 0) }
    var duration by remember(editing) { mutableStateOf(if (editing != null) ((editing.end - editing.start) / 60_000L).toInt().coerceAtLeast(1) else 60) }
    var reminder by remember(editing) { mutableStateOf(10) }
    var recurrence by remember(editing) { mutableStateOf(editing?.rrule ?: "") }
    var attendees by remember(editing) { mutableStateOf("") }
    var calendarMenu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var editorVisible by remember { mutableStateOf(false) }
    val haptic = LocalView.current

    LaunchedEffect(Unit) { editorVisible = true }

    fun closeEditor() {
        editorVisible = false
    }

    if (showDatePicker) {
        androidx.compose.runtime.key(showDatePicker, startDate) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = startDate.toDatePickerMillis()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let { millis ->
                                startDate = millisToLocalDate(millis, allDay = true)
                            }
                            showDatePicker = false
                        }
                    ) { Text("Aceptar") }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text("Cancelar") }
                }
            ) {
                DatePicker(
                    state = datePickerState,
                    showModeToggle = false,
                    title = { Text("Selecciona una fecha") },
                    headline = {
                        Text(
                            startDate.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", esLocale))
                                .replaceFirstChar { it.uppercase(esLocale) }
                        )
                    }
                )
            }
        }
    }

    Dialog(
        onDismissRequest = { closeEditor() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        AnimatedVisibility(
            visible = editorVisible,
            enter = fadeIn(tween(160)) + scaleIn(animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.94f),
            exit = fadeOut(tween(120)) + scaleOut(animationSpec = tween(160, easing = FastOutSlowInEasing), targetScale = 0.94f),
            modifier = Modifier.fillMaxSize()
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
                    IconButton(onClick = { closeEditor() }) { Icon(Icons.Rounded.Close, "Cerrar") }
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
                        OutlinedButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                showDatePicker = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                Text("Fecha", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                Text(
                                    startDate.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", esLocale))
                                        .replaceFirstChar { it.uppercase(esLocale) },
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Icon(Icons.Rounded.ExpandMore, contentDescription = "Seleccionar fecha")
                        }
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
                        TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK); vm.delete(editing.id); closeEditor() }) { Text("Eliminar") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { closeEditor() }) { Text("Cancelar") }
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            val zone = ZoneId.systemDefault()
                            val start = if (allDay) startDate.atAllDayStartMillis() else startDate.atTime(startHour, startMinute).atZone(zone).toInstant().toEpochMilli()
                            val end = if (allDay) startDate.atAllDayEndMillis() else start + duration * 60_000L
                            val draft = EventDraft(
                                title.ifBlank { "Sin título" }, calendarId, start, end, allDay, location, description,
                                reminder, recurrence.ifBlank { null }, attendees.split(",").map { it.trim() }.filter { it.contains("@") }
                            )
                            if (editing == null) vm.create(draft) else vm.update(editing.id, draft)
                            editorVisible = false
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

    LaunchedEffect(editorVisible) {
        if (!editorVisible) {
            kotlinx.coroutines.delay(170)
            onDismiss()
        }
    }
}

fun expressiveScheme(dark: Boolean): ColorScheme = if (!dark) {
    lightColorScheme(
        primary = Color(0xFF4F64FF),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFDCE1FF),
        onPrimaryContainer = Color(0xFF07144E),
        secondaryContainer = Color(0xFFDDE2FF),
        onSecondaryContainer = Color(0xFF101B4F),
        surface = Color(0xFFFAF8FF),
        surfaceContainer = Color(0xFFF0EEF6),
        surfaceContainerHigh = Color(0xFFE9E7EF),
        surfaceContainerLow = Color(0xFFF5F2FA),
        background = Color(0xFFFAF8FF)
    )
} else {
    darkColorScheme(
        primary = Color(0xFFB9C2FF),
        onPrimary = Color(0xFF17245E),
        primaryContainer = Color(0xFF3549A0),
        onPrimaryContainer = Color(0xFFE0E4FF),
        secondaryContainer = Color(0xFF3E466D),
        onSecondaryContainer = Color(0xFFE0E5FF),
        surface = Color(0xFF121318),
        surfaceContainer = Color(0xFF1D1E24),
        surfaceContainerHigh = Color(0xFF27282F),
        surfaceContainerLow = Color(0xFF191A20),
        background = Color(0xFF121318)
    )
}
