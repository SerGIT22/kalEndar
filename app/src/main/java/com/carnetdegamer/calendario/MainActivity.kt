package com.carnetdegamer.calendario

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carnetdegamer.calendario.data.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CalendarApp() }
    }
}

class CalendarViewModel : ViewModel() {
    private var repo: CalendarRepository? = null
    var calendars by mutableStateOf<List<CalendarInfo>>(emptyList()); private set
    var events by mutableStateOf<List<CalendarEvent>>(emptyList()); private set
    var ready by mutableStateOf(false); private set
    fun attach(r: CalendarRepository) { if (repo == null) repo = r; refresh() }
    fun refresh(focus: LocalDate = LocalDate.now()) {
        val r = repo ?: return
        calendars = r.calendars()
        val from = focus.minusMonths(2).withDayOfMonth(1).atStartMillis()
        val to = focus.plusMonths(2).withDayOfMonth(1).atStartMillis()
        events = r.events(from, to)
        ready = true
    }
    fun create(draft: EventDraft) { repo?.insert(draft); refresh(millisToLocalDate(draft.startMillis)) }
    fun update(id: Long, draft: EventDraft) { repo?.update(id, draft); refresh(millisToLocalDate(draft.startMillis)) }
    fun delete(id: Long) { repo?.delete(id); refresh() }
}

enum class CalendarView { MONTH, WEEK, DAY, AGENDA }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarApp(vm: CalendarViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> hasPermission = result[Manifest.permission.READ_CALENDAR] == true }
    LaunchedEffect(hasPermission) { if (hasPermission) vm.attach(CalendarRepository(context.contentResolver)) }
    MaterialTheme(colorScheme = expressiveScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!hasPermission) PermissionScreen { launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }
            else CalendarScreen(vm)
        }
    }
}

@Composable
fun PermissionScreen(onGrant: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CalendarMonth, null, tint = Color.White, modifier = Modifier.size(58.dp)) }
        Spacer(Modifier.height(28.dp)); Text("Calendario", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp)); Text("Conecta tus calendarios del teléfono, incluido Google Calendar cuando esté sincronizado.", textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp)); Button(onClick = onGrant, shape = RoundedCornerShape(22.dp)) { Text("Conceder acceso") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: CalendarViewModel) {
    var view by remember { mutableStateOf(CalendarView.MONTH) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var showCreator by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    val eventsForDay = vm.events.filter { millisToLocalDate(it.start) == date }
    val title = when (view) {
        CalendarView.MONTH -> date.month.getDisplayName(TextStyle.FULL, Locale("es", "ES")).replaceFirstChar { it.uppercase() }
        else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("es", "ES")).replaceFirstChar { it.uppercase() }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        FloatingBottomBar(view = view, onView = { view = it }, onAdd = { editing = null; showCreator = true })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("${date.dayOfMonth} de ${date.month.getDisplayName(TextStyle.FULL, Locale("es", "ES"))}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { date = LocalDate.now(); vm.refresh(date) }) { Icon(Icons.Rounded.Today, "Hoy") }
                IconButton(onClick = { vm.refresh(date) }) { Icon(Icons.Rounded.Sync, "Sincronizar") }
            }
            Spacer(Modifier.height(12.dp))
            ViewSwitcher(view, date, onDate = { date = it; vm.refresh(it) }, vm.events, onEvent = { editing = it; showCreator = true })
        }
    }
    if (showCreator) EventEditorDialog(vm, date, editing, onDismiss = { showCreator = false }, onSaved = { showCreator = false })
}

@Composable
fun ViewSwitcher(view: CalendarView, date: LocalDate, onDate: (LocalDate) -> Unit, events: List<CalendarEvent>, onEvent: (CalendarEvent) -> Unit) {
    AnimatedContent(view, transitionSpec = { (slideIntoContainer(androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Left) togetherWith slideOutOfContainer(androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection.Left)) }, label = "view") { v ->
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
    val first = month.withDayOfMonth(1)
    val offset = first.dayOfWeek.value - 1
    val days = (0 until offset).map { first.minusDays((offset - it).toLong()) } + (1..month.lengthOfMonth()).map { month.withDayOfMonth(it) }
    val padded = days + (0 until ((7 - days.size % 7) % 7)).map { days.last().plusDays((it + 1).toLong()) }
    Column(Modifier.fillMaxWidth().pointerInput(Unit) { detectHorizontalDragGestures { _, drag -> if (drag > 120) { month = month.minusMonths(1); onDate(month) } else if (drag < -120) { month = month.plusMonths(1); onDate(month) } } }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { month = month.minusMonths(1); onDate(month) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Mes anterior") }
            Text("${month.month.getDisplayName(TextStyle.FULL, Locale("es", "ES")).replaceFirstChar { it.uppercase() }} ${month.year}", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            IconButton(onClick = { month = month.plusMonths(1); onDate(month) }) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Mes siguiente") }
        }
        Row(Modifier.fillMaxWidth()) { listOf("L","M","X","J","V","S","D").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold) } }
        Spacer(Modifier.height(6.dp))
        LazyVerticalGrid(GridCells.Fixed(7), modifier = Modifier.heightIn(max = 390.dp)) {
            items(padded) { day ->
                val selected = day == date
                val dayEvents = events.filter { millisToLocalDate(it.start) == day }
                Column(Modifier.padding(3.dp).clip(RoundedCornerShape(16.dp)).clickable { onDate(day) }.background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(day.dayOfMonth.toString(), color = if (day.month != month.month) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    Row(Modifier.height(9.dp), horizontalArrangement = Arrangement.Center) { dayEvents.take(3).forEach { Box(Modifier.padding(horizontal = 1.dp).size(5.dp).clip(CircleShape).background(Color(it.color))) } }
                }
            }
        }
        Spacer(Modifier.height(10.dp)); Text("${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale("es","ES")).replaceFirstChar { it.uppercase() }} ${date.dayOfMonth}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        EventList(events.filter { millisToLocalDate(it.start) == date }, onEvent)
    }
}

@Composable
fun WeekView(date: LocalDate, events: List<CalendarEvent>, onDate: (LocalDate) -> Unit, onEvent: (CalendarEvent) -> Unit) {
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (0..6).forEach { i ->
            val d = monday.plusDays(i.toLong()); val selected = d == date
            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onDate(d) }.background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("es","ES")).take(2), fontSize = 11.sp); Text(d.dayOfMonth.toString(), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp)); events.filter { millisToLocalDate(it.start) == d }.take(3).forEach { Box(Modifier.size(6.dp).clip(CircleShape).background(Color(it.color)).padding(2.dp)) }
            }
        }
    }
    Spacer(Modifier.height(12.dp)); EventList(events.filter { val d=millisToLocalDate(it.start); !d.isBefore(monday) && d.isBefore(monday.plusDays(7)) }, onEvent)
}

@Composable
fun DayView(date: LocalDate, events: List<CalendarEvent>, onEvent: (CalendarEvent) -> Unit) {
    val dayEvents = events.filter { millisToLocalDate(it.start) == date }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items((0..23).toList()) { hour ->
            val es = dayEvents.filter { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).hour == hour }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { Text(String.format("%02d:00", hour), Modifier.width(54.dp), color = MaterialTheme.colorScheme.outline, fontSize = 12.sp); Column(Modifier.weight(1f)) { es.forEach { EventCard(it, onEvent) }; Divider() } }
        }
    }
}

@Composable
fun AgendaView(date: LocalDate, events: List<CalendarEvent>, onEvent: (CalendarEvent) -> Unit) {
    val sorted = events.filter { !millisToLocalDate(it.start).isBefore(date.minusDays(7)) && !millisToLocalDate(it.start).isAfter(date.plusDays(30)) }.sortedBy { it.start }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(sorted) { EventCard(it, onEvent) } }
}

@Composable
fun EventList(events: List<CalendarEvent>, onEvent: (CalendarEvent) -> Unit) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { events.forEach { EventCard(it, onEvent) } } }

@Composable
fun EventCard(e: CalendarEvent, onEvent: (CalendarEvent) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { onEvent(e) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(42.dp).clip(RoundedCornerShape(4.dp)).background(Color(e.color))); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(e.title, fontWeight = FontWeight.SemiBold); Text(if (e.allDay) "Todo el día" else timeText(e.start) + " – " + timeText(e.end), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp); if (e.location.isNotBlank()) Text(e.location, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
    }
}

fun timeText(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))

@Composable
fun FloatingBottomBar(view: CalendarView, onView: (CalendarView) -> Unit, onAdd: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(7.dp), verticalAlignment = Alignment.CenterVertically) {
            NavItem("Hoy", Icons.Rounded.Today, view == CalendarView.DAY) { onView(CalendarView.DAY) }
            NavItem("Agenda", Icons.Rounded.ViewAgenda, view == CalendarView.AGENDA) { onView(CalendarView.AGENDA) }
            FloatingActionButton(onClick = onAdd, modifier = Modifier.size(54.dp), shape = CircleShape, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) { Icon(Icons.Rounded.Add, "Crear evento") }
            NavItem("Mes", Icons.Rounded.CalendarMonth, view == CalendarView.MONTH) { onView(CalendarView.MONTH) }
            NavItem("Semana", Icons.Rounded.ViewWeek, view == CalendarView.WEEK) { onView(CalendarView.WEEK) }
        }
    }
}

@Composable
fun RowScope.NavItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable { onClick() }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant); Text(label, fontSize = 10.sp, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorDialog(vm: CalendarViewModel, selectedDate: LocalDate, editing: CalendarEvent?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var title by remember(editing) { mutableStateOf(editing?.title ?: "") }
    var location by remember(editing) { mutableStateOf(editing?.location ?: "") }
    var description by remember(editing) { mutableStateOf(editing?.description ?: "") }
    var allDay by remember(editing) { mutableStateOf(editing?.allDay ?: false) }
    var calendarId by remember(editing, vm.calendars) { mutableStateOf(editing?.calendarId ?: vm.calendars.firstOrNull { it.writable }?.id ?: vm.calendars.firstOrNull()?.id ?: -1L) }
    var startDate by remember(editing) { mutableStateOf(editing?.let { millisToLocalDate(it.start) } ?: selectedDate) }
    var startHour by remember(editing) { mutableStateOf(editing?.let { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).hour } ?: 10) }
    var startMinute by remember(editing) { mutableStateOf(editing?.let { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).minute } ?: 0) }
    var duration by remember(editing) { mutableStateOf(60) }
    var reminder by remember { mutableStateOf(10) }
    var recurrence by remember(editing) { mutableStateOf("") }
    var attendees by remember { mutableStateOf("") }
    var calendarMenu by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = {
        Button(onClick = {
            val zone = ZoneId.systemDefault(); val start = if (allDay) startDate.atStartMillis() else startDate.atTime(startHour,startMinute).atZone(zone).toInstant().toEpochMilli(); val end = if (allDay) startDate.atEndMillis() else start + duration*60_000L
            val draft = EventDraft(title.ifBlank { "Sin título" }, calendarId, start, end, allDay, location, description, reminder, recurrence.ifBlank { null }, attendees.split(",").map { it.trim() }.filter { it.contains("@") })
            if (editing == null) vm.create(draft) else vm.update(editing.id, draft); onSaved()
        }, enabled = title.isNotBlank() && calendarId >= 0 && vm.calendars.any { it.id == calendarId && it.writable }) { Text(if (editing == null) "Crear" else "Guardar") }
    }, dismissButton = { Row { if (editing != null) TextButton(onClick = { vm.delete(editing.id); onDismiss() }) { Text("Eliminar") }; TextButton(onClick = onDismiss) { Text("Cancelar") } } }, title = { Text(if (editing == null) "Nuevo evento" else "Editar evento") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max=560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label={Text("Título")}, singleLine=true, modifier=Modifier.fillMaxWidth())
            Box { OutlinedButton(onClick={calendarMenu=true}, modifier=Modifier.fillMaxWidth(), shape=RoundedCornerShape(16.dp)) { Text(vm.calendars.firstOrNull { it.id==calendarId }?.let { "${it.name} · ${it.account}" } ?: "Calendario") }; DropdownMenu(calendarMenu,{calendarMenu=false}) { vm.calendars.filter{it.writable}.forEach { DropdownMenuItem(text={Text(it.name)}, onClick={calendarId=it.id;calendarMenu=false}) } } }
            Row(verticalAlignment=Alignment.CenterVertically){ Text("Todo el día",Modifier.weight(1f)); Switch(allDay,{allDay=it}) }
            OutlinedTextField(startDate.toString(), { runCatching { startDate=LocalDate.parse(it) } }, label={Text("Fecha (AAAA-MM-DD)")}, singleLine=true, modifier=Modifier.fillMaxWidth())
            if (!allDay) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedTextField(startHour.toString(),{startHour=it.toIntOrNull()?.coerceIn(0,23)?:startHour},label={Text("Hora")},modifier=Modifier.weight(1f)); OutlinedTextField(startMinute.toString(),{startMinute=it.toIntOrNull()?.coerceIn(0,59)?:startMinute},label={Text("Min")},modifier=Modifier.weight(1f)); OutlinedTextField(duration.toString(),{duration=it.toIntOrNull()?.coerceAtLeast(1)?:duration},label={Text("Minutos")},modifier=Modifier.weight(1f)) }
            OutlinedTextField(location,{location=it},label={Text("Ubicación")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(description,{description=it},label={Text("Descripción")},minLines=2,maxLines=3,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(attendees,{attendees=it},label={Text("Invitados (emails separados por coma)")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(reminder.toString(),{reminder=it.toIntOrNull()?.coerceAtLeast(0)?:reminder},label={Text("Recordatorio: minutos antes")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(recurrence,{recurrence=it},label={Text("Repetición RRULE (opcional)")},singleLine=true,modifier=Modifier.fillMaxWidth())
        }
    })
}

fun expressiveScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF246BFE), onPrimary = Color.White, primaryContainer = Color(0xFFD9E2FF), onPrimaryContainer = Color(0xFF001A41),
    secondaryContainer = Color(0xFFE0E2EC), surface = Color(0xFFF9F9FF), surfaceContainer = Color(0xFFEFEFF6), surfaceContainerHigh = Color(0xFFE8E8F0),
    background = Color(0xFFF9F9FF)
)
