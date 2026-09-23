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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carnetdegamer.calendario.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var eventsByDate by mutableStateOf<Map<LocalDate, List<CalendarEvent>>>(emptyMap()); private set
    var eventDotsByDate by mutableStateOf<Map<LocalDate, List<Int>>>(emptyMap()); private set
    var ready by mutableStateOf(false); private set

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

            // All expensive date conversion/indexing stays off the Compose thread.
            val byDate = loadedEvents.groupBy { eventLocalDate(it) }
            val dotsByDate = byDate.mapValues { (_, dayEvents) ->
                dayEvents.asSequence().map { it.color }.distinct().take(3).toList()
            }

            withContext(Dispatchers.Main.immediate) {
                calendars = loadedCalendars
                events = loadedEvents
                eventsByDate = byDate
                eventDotsByDate = dotsByDate
                loadedFrom = from.toLocalDate()
                loadedTo = to.toLocalDate()
                ready = true
            }
        }
    }

    fun ensureRange(focus: LocalDate) {
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
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
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
            if (!hasPermission) {
                PermissionScreen {
                    launcher.launch(
                        arrayOf(
                            Manifest.permission.READ_CALENDAR,
                            Manifest.permission.WRITE_CALENDAR
                        )
                    )
                }
            } else {
                CalendarScreen(vm)
            }
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
            Icon(
                Icons.Rounded.CalendarMonth,
                null,
                tint = Color.White,
                modifier = Modifier.size(60.dp)
            )
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
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onGrant()
            },
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
        CalendarView.MONTH -> date.month.getDisplayName(DateTextStyle.FULL, esLocale)
            .replaceFirstChar { it.uppercase(esLocale) }
        else -> date.dayOfWeek.getDisplayName(DateTextStyle.FULL, esLocale)
            .replaceFirstChar { it.uppercase(esLocale) }
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
                            (slideInVertically(tween(180, easing = FastOutSlowInEasing)) { it / 2 } +
                                fadeIn(tween(140))) togetherWith
                                (slideOutVertically(tween(140)) { -it / 3 } + fadeOut(tween(100)))
                        },
                        label = "calendar_title"
                    ) { animatedTitle ->
                        Text(
                            animatedTitle,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        "${date.dayOfMonth} de ${date.month.getDisplayName(DateTextStyle.FULL, esLocale)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    date = LocalDate.now()
                    vm.refresh(date)
                }) { Icon(Icons.Rounded.Today, "Hoy") }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    vm.refresh(date)
                }) { Icon(Icons.Rounded.Sync, "Sincronizar") }
            }
            Spacer(Modifier.height(10.dp))
            ViewSwitcher(
                view = view,
                date = date,
                onDate = {
                    haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    date = it
                    vm.ensureRange(it)
                },
                events = vm.events,
                eventsByDate = vm.eventsByDate,
                eventDotsByDate = vm.eventDotsByDate,
                onEvent = { editing = it; showCreator = true }
            )
        }
    }

    Box(
        Modifier.fillMaxSize().navigationBarsPadding().padding(end = 16.dp, bottom = 106.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
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
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 5.dp,
                pressedElevation = 8.dp
            )
        ) { Icon(Icons.Rounded.Add, "Nuevo evento", modifier = Modifier.size(24.dp)) }
    }

    if (showCreator) {
        EventEditorDialog(
            vm = vm,
            selectedDate = date,
            editing = editing,
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
    eventsByDate: Map<LocalDate, List<CalendarEvent>>,
    eventDotsByDate: Map<LocalDate, List<Int>>,
    onEvent: (CalendarEvent) -> Unit
) {
    when (view) {
        CalendarView.MONTH -> MonthView(
            date,
            eventsByDate,
            eventDotsByDate,
            onDate,
            onEvent
        )
        CalendarView.WEEK -> WeekView(date, eventsByDate, onDate, onEvent)
        CalendarView.DAY -> DayView(date, eventsByDate, onEvent)
        CalendarView.AGENDA -> AgendaView(date, events, onEvent)
    }
}

/**
 * Performance-critical month view.
 *
 * The previous implementation created 42 Compose subtrees containing Column,
 * Text, Row, Box, clip and clickable modifiers. That is unnecessary for a
 * fixed calendar grid and can become expensive when the selected date and the
 * event maps change together.
 *
 * This version renders the entire 42-cell grid with ONE Canvas. The only
 * Compose work per frame is drawing primitives and text. Event lookup remains
 * O(1) through the ViewModel's prebuilt map.
 */
@Composable
fun MonthView(
    date: LocalDate,
    eventsByDate: Map<LocalDate, List<CalendarEvent>>,
    eventDotsByDate: Map<LocalDate, List<Int>>,
    onDate: (LocalDate) -> Unit,
    onEvent: (CalendarEvent) -> Unit
) {
    var month by remember(date.year, date.month) {
        mutableStateOf(date.withDayOfMonth(1))
    }
    val haptic = LocalView.current
    val textMeasurer = rememberTextMeasurer()
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val days = remember(month) {
        val first = month.withDayOfMonth(1)
        val leading = first.dayOfWeek.value - 1
        List(42) { index ->
            if (index < leading) {
                first.minusDays((leading - index).toLong())
            } else {
                first.plusDays((index - leading).toLong())
            }
        }
    }

    val selectedEvents = eventsByDate[date].orEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .pointerInput(month) {
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
                "${month.month.getDisplayName(DateTextStyle.FULL, esLocale).replaceFirstChar { it.uppercase(esLocale) }} ${month.year}",
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
                    color = onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
            }
        }

        // The grid is one composable instead of 42 independently composed cells.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
                .pointerInput(month, date, eventDotsByDate) {
                    detectTapGestures { offset ->
                        val cellWidth = size.width / 7f
                        val cellHeight = size.height / 6f
                        val column = (offset.x / cellWidth).toInt().coerceIn(0, 6)
                        val row = (offset.y / cellHeight).toInt().coerceIn(0, 5)
                        val index = row * 7 + column
                        val tappedDay = days[index]
                        haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onDate(tappedDay)
                    }
                }
        ) {
            drawMonthGrid(
                days = days,
                selectedDate = date,
                month = month,
                eventDotsByDate = eventDotsByDate,
                textMeasurer = textMeasurer,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                onSurface = onSurface,
                outline = outline
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "${date.dayOfWeek.getDisplayName(DateTextStyle.FULL, esLocale).replaceFirstChar { it.uppercase(esLocale) }} ${date.dayOfMonth}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(5.dp))
        EventList(selectedEvents, onEvent)
    }
}

private fun DrawScope.drawMonthGrid(
    days: List<LocalDate>,
    selectedDate: LocalDate,
    month: LocalDate,
    eventDotsByDate: Map<LocalDate, List<Int>>,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    onSurface: Color,
    outline: Color
) {
    val cellWidth = size.width / 7f
    val cellHeight = size.height / 6f

    days.forEachIndexed { index, day ->
        val column = index % 7
        val row = index / 7
        val left = column * cellWidth
        val top = row * cellHeight
        val centerX = left + cellWidth / 2f

        if (day == selectedDate) {
            drawRoundRect(
                color = primaryContainer,
                topLeft = androidx.compose.ui.geometry.Offset(left + 1f, top + 1f),
                size = androidx.compose.ui.geometry.Size(cellWidth - 2f, cellHeight - 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f)
            )
        }

        val dayColor = when {
            day == selectedDate -> onPrimaryContainer
            day.month != month.month -> outline
            else -> onSurface
        }
        val dayStyle = TextStyle(
            fontSize = 13.sp,
            fontWeight = if (day == selectedDate) FontWeight.Bold else FontWeight.Normal,
            color = dayColor
        )
        val layout = textMeasurer.measure(AnnotatedString(day.dayOfMonth.toString()), dayStyle)
        drawText(
            layout,
            topLeft = androidx.compose.ui.geometry.Offset(
                centerX - layout.size.width / 2f,
                top + 3.dp.toPx()
            )
        )

        val colors = eventDotsByDate[day].orEmpty()
        if (colors.isNotEmpty()) {
            val dotSize = 4.dp.toPx()
            val gap = 2.dp.toPx()
            val totalWidth = colors.size * dotSize + (colors.size - 1) * gap
            var x = centerX - totalWidth / 2f + dotSize / 2f
            val dotY = top + 29.dp.toPx()
            colors.forEach { color ->
                drawCircle(Color(color), radius = dotSize / 2f, center = androidx.compose.ui.geometry.Offset(x, dotY))
                x += dotSize + gap
            }
        }
    }
}

@Composable
fun WeekView(
    date: LocalDate,
    eventsByDate: Map<LocalDate, List<CalendarEvent>>,
    onDate: (LocalDate) -> Unit,
    onEvent: (CalendarEvent) -> Unit
) {
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    val haptic = LocalView.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (0..6).forEach { i ->
            val d = monday.plusDays(i.toLong())
            val selected = d == date
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onDate(d)
                    }
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer
                    )
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(d.dayOfWeek.getDisplayName(DateTextStyle.SHORT, esLocale).take(2), fontSize = 11.sp)
                Text(d.dayOfMonth.toString(), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Row {
                    eventsByDate[d].orEmpty().take(3).forEach {
                        Box(
                            Modifier
                                .padding(horizontal = 1.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(Color(it.color))
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    EventList(
        (0..6)
            .flatMap { eventsByDate[monday.plusDays(it.toLong())].orEmpty() }
            .sortedBy { it.start },
        onEvent
    )
}

@Composable
fun DayView(
    date: LocalDate,
    eventsByDate: Map<LocalDate, List<CalendarEvent>>,
    onEvent: (CalendarEvent) -> Unit
) {
    val dayEvents = eventsByDate[date].orEmpty().sortedBy { it.start }
    val allDay = dayEvents.filter { it.allDay }
    val timed = dayEvents.filterNot { it.allDay }
    val zone = ZoneId.systemDefault()
    val eventsByHour = remember(timed) {
        timed.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).hour }
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        if (allDay.isNotEmpty()) {
            item {
                Text(
                    "Todo el día",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 7.dp)
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(bottom = 14.dp)
                ) {
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
            .filter {
                !eventLocalDate(it).isBefore(date.minusDays(7)) &&
                    !eventLocalDate(it).isAfter(date.plusDays(30))
            }
            .sortedBy { it.start }
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 20.dp)
    ) {
        items(items = sorted, key = { it.id }) { EventCard(it, onEvent) }
    }
}

@Composable
fun EventList(events: List<CalendarEvent>, onEvent: (CalendarEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        events.forEach { EventCard(it, onEvent) }
    }
}

@Composable
fun EventCard(e: CalendarEvent, onEvent: (CalendarEvent) -> Unit) {
    val haptic = LocalView.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                onEvent(e)
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(42.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(e.color))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, fontWeight = FontWeight.SemiBold)
            Text(
                if (e.allDay) "Todo el día" else timeText(e.start) + " – " + timeText(e.end),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            if (e.location.isNotBlank()) {
                Text(
                    e.location,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }
    }
}

fun timeText(millis: Long) = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
fun FloatingBottomBar(view: CalendarView, onView: (CalendarView) -> Unit) {
    val hapticView = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
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
        val outerPadding = 6.dp
        val indicatorWidth = 100.dp
        val indicatorHeight = 48.dp
        val contentWidth = maxWidth - outerPadding * 2
        val slotWidth = contentWidth / 4f
        val targetOffset = outerPadding + slotWidth * selectedIndex + (slotWidth - indicatorWidth) / 2f
        val targetOffsetPx = with(density) { targetOffset.toPx() }
        val animatedOffsetPx by androidx.compose.animation.core.animateFloatAsState(
            targetValue = targetOffsetPx,
            animationSpec = tween(190, easing = FastOutSlowInEasing),
            label = "nav_indicator_x"
        )

        Surface(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            shape = RoundedCornerShape(36.dp)
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer { translationX = animatedOffsetPx }
                        .width(indicatorWidth)
                        .height(indicatorHeight)
                        .clip(RoundedCornerShape(26.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    val (label, icon) = items[selectedIndex].second
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = outerPadding, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items.forEachIndexed { index, (destination, data) ->
                        val (label, icon) = data
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(26.dp))
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null
                                ) {
                                    if (destination != view) {
                                        hapticView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        onView(destination)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .size(26.dp)
                                    .graphicsLayer {
                                        alpha = if (index == selectedIndex) 0f else 1f
                                    }
                            )
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
        mutableStateOf(
            editing?.calendarId
                ?: vm.calendars.firstOrNull { it.writable }?.id
                ?: vm.calendars.firstOrNull()?.id
                ?: -1L
        )
    }
    var startDate by remember(editing) {
        mutableStateOf(editing?.let { eventLocalDate(it) } ?: selectedDate)
    }
    var startHour by remember(editing) {
        mutableStateOf(
            editing?.let { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).hour } ?: 10
        )
    }
    var startMinute by remember(editing) {
        mutableStateOf(
            editing?.let { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).minute } ?: 0
        )
    }
    var duration by remember(editing) {
        mutableStateOf(
            if (editing != null) ((editing.end - editing.start) / 60_000L).toInt().coerceAtLeast(1)
            else 60
        )
    }
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
        key(showDatePicker, startDate) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = startDate.toDatePickerMillis()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            startDate = millisToLocalDate(millis, allDay = true)
                        }
                        showDatePicker = false
                    }) { Text("Aceptar") }
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
                            startDate.format(
                                DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", esLocale)
                            ).replaceFirstChar { it.uppercase(esLocale) }
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
            enter = fadeIn(tween(160)) +
                scaleIn(
                    animationSpec = spring(
                        dampingRatio = 0.82f,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
                    ),
                    initialScale = 0.94f
                ),
            exit = fadeOut(tween(120)) +
                scaleOut(
                    animationSpec = tween(160, easing = FastOutSlowInEasing),
                    targetScale = 0.94f
                ),
            modifier = Modifier.fillMaxSize()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .safeDrawingPadding()
                    .padding(horizontal = 12.dp),
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
                            Text(
                                if (editing == null) "Nuevo evento" else "Editar evento",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Calendario",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { closeEditor() }) {
                            Icon(Icons.Rounded.Close, "Cerrar")
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            OutlinedTextField(
                                title,
                                { title = it },
                                label = { Text("Título") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            )
                        }
                        item {
                            Box {
                                OutlinedButton(
                                    onClick = { calendarMenu = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text(
                                        vm.calendars.firstOrNull { it.id == calendarId }
                                            ?.let { "${it.name} · ${it.account}" } ?: "Calendario",
                                        Modifier.weight(1f),
                                        textAlign = TextAlign.Start
                                    )
                                    Icon(Icons.Rounded.ExpandMore, null)
                                }
                                DropdownMenu(
                                    expanded = calendarMenu,
                                    onDismissRequest = { calendarMenu = false }
                                ) {
                                    vm.calendars.filter { it.writable }.forEach {
                                        DropdownMenuItem(
                                            text = { Text(it.name) },
                                            onClick = {
                                                calendarId = it.id
                                                calendarMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Todo el día", fontWeight = FontWeight.Medium)
                                    Text(
                                        "Sin hora de inicio ni fin",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                                Switch(
                                    checked = allDay,
                                    onCheckedChange = {
                                        haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        allDay = it
                                    }
                                )
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
                                        startDate.format(
                                            DateTimeFormatter.ofPattern(
                                                "EEEE, d 'de' MMMM 'de' yyyy",
                                                esLocale
                                            )
                                        ).replaceFirstChar { it.uppercase(esLocale) },
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Icon(Icons.Rounded.ExpandMore, contentDescription = "Seleccionar fecha")
                            }
                        }
                        if (!allDay) {
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        startHour.toString().padStart(2, '0'),
                                        { startHour = it.toIntOrNull()?.coerceIn(0, 23) ?: startHour },
                                        label = { Text("Hora") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(18.dp)
                                    )
                                    OutlinedTextField(
                                        startMinute.toString().padStart(2, '0'),
                                        { startMinute = it.toIntOrNull()?.coerceIn(0, 59) ?: startMinute },
                                        label = { Text("Min") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(18.dp)
                                    )
                                    OutlinedTextField(
                                        duration.toString(),
                                        { duration = it.toIntOrNull()?.coerceAtLeast(1) ?: duration },
                                        label = { Text("Duración") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1.25f),
                                        shape = RoundedCornerShape(18.dp)
                                    )
                                }
                            }
                        }
                        item {
                            OutlinedTextField(
                                location,
                                { location = it },
                                label = { Text("Ubicación") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            )
                        }
                        item {
                            OutlinedTextField(
                                description,
                                { description = it },
                                label = { Text("Descripción") },
                                minLines = 3,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            )
                        }
                        item {
                            OutlinedTextField(
                                attendees,
                                { attendees = it },
                                label = { Text("Invitados · emails separados por coma") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            )
                        }
                        item {
                            OutlinedTextField(
                                reminder.toString(),
                                { reminder = it.toIntOrNull()?.coerceAtLeast(0) ?: reminder },
                                label = { Text("Recordatorio · minutos antes") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            )
                        }
                        item {
                            OutlinedTextField(
                                recurrence,
                                { recurrence = it },
                                label = { Text("Repetición · RRULE opcional") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (editing != null) {
                            TextButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                vm.delete(editing.id)
                                closeEditor()
                            }) { Text("Eliminar") }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { closeEditor() }) { Text("Cancelar") }
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                val zone = ZoneId.systemDefault()
                                val start = if (allDay) {
                                    startDate.atAllDayStartMillis()
                                } else {
                                    startDate.atTime(startHour, startMinute)
                                        .atZone(zone).toInstant().toEpochMilli()
                                }
                                val end = if (allDay) {
                                    startDate.atAllDayEndMillis()
                                } else {
                                    start + duration * 60_000L
                                }
                                val draft = EventDraft(
                                    title.ifBlank { "Sin título" },
                                    calendarId,
                                    start,
                                    end,
                                    allDay,
                                    location,
                                    description,
                                    reminder,
                                    recurrence.ifBlank { null },
                                    attendees.split(",")
                                        .map { it.trim() }
                                        .filter { it.contains("@") }
                                )
                                if (editing == null) vm.create(draft) else vm.update(editing.id, draft)
                                editorVisible = false
                                onSaved()
                            },
                            enabled = title.isNotBlank() &&
                                calendarId >= 0 &&
                                vm.calendars.any { it.id == calendarId && it.writable },
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
