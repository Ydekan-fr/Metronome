package com.ydeclik.app

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

val Orange = Color(0xFFFF9500)
val Bg = Color(0xFF171719)

@Composable
fun YdeclikTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Orange, onPrimary = Color.Black,
            background = Bg, surface = Color(0xFF232326), onSurface = Color.White
        ),
        content = content
    )
}

// ------------------------------------------------------------------ root

@Composable
fun YdeclikApp(vm: MetronomeViewModel) {
    val view = LocalView.current
    SideEffect { view.keepScreenOn = vm.isPlaying && vm.keepScreenOn }
    LaunchedEffect(vm.fullscreen) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(window, view)
        if (vm.fullscreen) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    var showSettings by remember { mutableStateOf(false) }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TopBar(vm)
            TempoSection(vm)
            BeatsRow(vm)
            AutoPanel(vm)
            PresetsRow(vm)
            Transport(vm) { showSettings = true }
        }
    }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
}

// ------------------------------------------------------------------ helpers

@Composable
fun Pill(text: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) Orange else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun Tag(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
}

/** Number you can scrub vertically (drag) or tap to type. */
@Composable
fun ScrubNumber(
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
    fontSize: TextUnit,
    width: Dp,
    weight: FontWeight = FontWeight.SemiBold,
    pxPerStep: Float = 20f,
    signed: Boolean = false,
    trigger: Int = 0,
    onEditing: (Boolean) -> Unit = {}
) {
    val curValue by rememberUpdatedState(value)
    val curOnChange by rememberUpdatedState(onChange)
    val curEditing by rememberUpdatedState(onEditing)
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var hadFocus by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val initialTrigger = remember { trigger }

    fun clamp(v: Int) = v.coerceIn(range.first, range.last)
    fun begin() {
        text = curValue.toString()
        hadFocus = false
        editing = true
        curEditing(true)
    }
    fun commit() {
        if (!editing) return
        editing = false
        curEditing(false)
        text.trim().toIntOrNull()?.let { curOnChange(clamp(it)) }
    }

    LaunchedEffect(trigger) { if (trigger != initialTrigger) begin() }

    Box(Modifier.width(width), contentAlignment = Alignment.Center) {
        if (editing) {
            BasicTextField(
                value = text,
                onValueChange = { s -> text = s.filter { it.isDigit() || (signed && (it == '-' || it == '+')) } },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White, fontSize = fontSize, fontWeight = weight, textAlign = TextAlign.Center
                ),
                cursorBrush = SolidColor(Orange),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (signed) KeyboardType.Text else KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .focusRequester(focus)
                    .onFocusChanged { st ->
                        if (st.isFocused) hadFocus = true
                        else if (hadFocus) commit()
                    }
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        } else {
            Text(
                text = if (signed && value > 0) "+$value" else "$value",
                color = Color.White, fontSize = fontSize, fontWeight = weight,
                modifier = Modifier
                    .pointerInput(Unit) { detectTapGestures(onTap = { begin() }) }
                    .pointerInput(Unit) {
                        var start = 0
                        var acc = 0f
                        detectVerticalDragGestures(
                            onDragStart = { start = curValue; acc = 0f },
                            onVerticalDrag = { _, dy ->
                                acc += dy
                                curOnChange(clamp(start + (-acc / pxPerStep).roundToInt()))
                            }
                        )
                    }
            )
        }
    }
}

/** Fires once on press, then repeats while held. */
@Composable
fun HoldBox(onHold: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val cur by rememberUpdatedState(onHold)
    val scope = rememberCoroutineScope()
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                cur()
                val job = scope.launch {
                    delay(400)
                    while (true) { cur(); delay(60) }
                }
                waitForUpOrCancellation()
                job.cancel()
            }
        },
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun NoteIcon(n: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val d = density
        val hw = 10f * d
        val hh = 7.5f * d
        val headY = size.height - 10f * d
        val topY = if (n == 3) 18f * d else 9f * d
        val stems = ArrayList<Float>()
        for (i in 0 until n) {
            val cx = size.width * (i + 1) / (n + 1)
            drawOval(color, topLeft = Offset(cx - hw / 2, headY - hh / 2), size = Size(hw, hh))
            val sx = cx + hw / 2 - 1f * d
            stems.add(sx)
            drawLine(color, Offset(sx, headY), Offset(sx, topY), strokeWidth = 1.8f * d)
        }
        if (n >= 2) {
            val f = stems.first()
            val l = stems.last()
            drawRect(color, Offset(f, topY), Size(l - f + 1.8f * d, 3.2f * d))
            if (n == 4) drawRect(color, Offset(f, topY + 5.5f * d), Size(l - f + 1.8f * d, 3.2f * d))
        }
    }
}

@Composable
fun MiniButton(symbol: String, on: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 22.dp, height = 22.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (on) tint else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (on) Color.Black else Color.White.copy(alpha = 0.6f)
        )
    }
}

// ------------------------------------------------------------------ sections

@Composable
fun TopBar(vm: MetronomeViewModel) {
    val cfg = vm.cfg
    val lightColor by animateColorAsState(
        if (vm.flash) (if (vm.flashAccent) Orange else Color(0xFF34C759)) else Color.White.copy(alpha = 0.08f),
        label = "light"
    )
    var menu by remember { mutableStateOf(false) }
    val groups = cfg.sig.groupings
    val groupingLabel = groups[cfg.grouping.coerceIn(0, groups.size - 1)].joinToString("+")

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Box(Modifier.width(90.dp)) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(lightColor)
                    .border(1.dp, Color.White.copy(alpha = if (vm.lightEnabled) 0.3f else 0.1f), CircleShape)
                    .clickable { vm.toggleLight() }
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                Text(
                    cfg.sig.label + " ▾", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.clickable { menu = true }
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    TimeSig.ALL.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label) }, onClick = { vm.setSig(s); menu = false })
                    }
                }
            }
            if (groups.size > 1) Pill(groupingLabel) { vm.cycleGrouping() }
        }
        Box(Modifier.width(90.dp), contentAlignment = Alignment.TopEnd) {
            Pill("TAP") { vm.tap() }
        }
    }
}

@Composable
fun TempoSection(vm: MetronomeViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HoldBox({ vm.nudge(1) }, Modifier.size(width = 140.dp, height = 48.dp)) {
            Text("▲", fontSize = 26.sp, color = Color.White)
        }
        ScrubNumber(
            value = vm.cfg.bpm, onChange = { vm.setBPM(it) }, range = 20..300,
            fontSize = 96.sp, width = 260.dp, weight = FontWeight.Light, pxPerStep = 12f,
            trigger = vm.tempoEditTrigger, onEditing = { vm.typing = it }
        )
        HoldBox({ vm.nudge(-1) }, Modifier.size(width = 140.dp, height = 48.dp)) {
            Text("▼", fontSize = 26.sp, color = Color.White)
        }
        Text("BPM · ${tempoName(vm.cfg.bpm)}", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BeatColumn(vm: MetronomeViewModel, b: Int) {
    val cfg = vm.cfg
    val subs = cfg.subs.getOrElse(b) { 1 }.coerceAtLeast(1)
    val current = vm.currentBeat == b
    val w = (max(3, subs) * 24).dp
    val isStart = b in cfg.sig.groupStarts(cfg.grouping)

    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = if (current) 0.10f else 0.04f))
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Tap: change subdivision · Long-press: accent the first subdivision
        Box(
            Modifier
                .size(w, 50.dp)
                .combinedClickable(
                    onClick = { vm.cycleSub(b) },
                    onLongClick = { vm.toggleNote(b, 0, NoteState.ACCENT) }
                )
        ) {
            NoteIcon(subs, if (current) Orange else Color.White.copy(alpha = 0.92f), Modifier.fillMaxSize())
            if (subs == 3) {
                Text(
                    "3", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = if (current) Orange else Color.White,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (k in 0 until subs) {
                val st = cfg.state(b, k)
                val live = current && vm.currentSub == k
                Column(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (live) Orange.copy(alpha = 0.25f) else Color.Transparent)
                        .padding(1.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    MiniButton(">", st == NoteState.ACCENT, Orange) { vm.toggleNote(b, k, NoteState.ACCENT) }
                    MiniButton("‖", st == NoteState.REST, Color.Gray) { vm.toggleNote(b, k, NoteState.REST) }
                }
            }
        }
        Text(
            "${b + 1}", fontSize = 11.sp,
            fontWeight = if (isStart) FontWeight.Bold else FontWeight.Normal,
            color = if (isStart) Orange else Color.White.copy(alpha = 0.5f)
        )
    }
}

@Composable
fun BeatsRow(vm: MetronomeViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        for (b in 0 until vm.cfg.sig.beats) BeatColumn(vm, b)
    }
}

@Composable
fun AutoPanel(vm: MetronomeViewModel) {
    val c = vm.cfg
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Pill("AUTO", on = c.autoOn) { vm.toggleAuto() }
        if (c.autoMode == AutoMode.MUTE) {
            Pill("MUTE") { vm.toggleAutoMode() }
            Tag("play")
            ScrubNumber(c.playBars, { vm.setPlayBars(it) }, 1..99, 15.sp, 36.dp, pxPerStep = 28f,
                onEditing = { vm.typing = it })
            Tag("bars · mute")
            ScrubNumber(c.muteBars, { vm.setMuteBars(it) }, 1..99, 15.sp, 36.dp, pxPerStep = 28f,
                onEditing = { vm.typing = it })
            Tag("bars")
        } else {
            Pill("ADD") { vm.toggleAutoMode() }
            ScrubNumber(c.addBpm, { vm.setAddBpm(it) }, -50..50, 15.sp, 44.dp, pxPerStep = 28f, signed = true,
                onEditing = { vm.typing = it })
            Tag("BPM every")
            ScrubNumber(c.addEvery, { vm.setAddEvery(it) }, 1..999, 15.sp, 44.dp, pxPerStep = 28f,
                onEditing = { vm.typing = it })
            Pill(if (c.addUnit == AddUnit.BAR) "BAR" else "SEC") { vm.toggleAddUnit() }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetsRow(vm: MetronomeViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (i in 0 until 9) {
                val filled = vm.presets[i] != null
                Box(
                    Modifier
                        .size(width = 34.dp, height = 38.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color.White.copy(alpha = if (filled) 0.18f else 0.06f))
                        .border(
                            1.5.dp,
                            if (vm.currentSlot == i) Orange else Color.Transparent,
                            RoundedCornerShape(7.dp)
                        )
                        .combinedClickable(onClick = { vm.loadPreset(i) }, onLongClick = { vm.savePreset(i) }),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${i + 1}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
        Text(
            "Tap: load · Long-press: save", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp
        )
    }
}

@Composable
fun Transport(vm: MetronomeViewModel, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (vm.muted) "🔇" else "🔊", fontSize = 20.sp,
                modifier = Modifier.clickable { vm.toggleMute() }.padding(end = 6.dp)
            )
            Slider(
                value = vm.volume, onValueChange = { vm.updateVolume(it) }, valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = Orange, activeTrackColor = Orange),
                modifier = Modifier.weight(1f)
            )
        }
        Box(
            Modifier
                .padding(horizontal = 12.dp)
                .size(68.dp)
                .clip(CircleShape)
                .background(Orange)
                .clickable { vm.toggle() },
            contentAlignment = Alignment.Center
        ) {
            Text(if (vm.isPlaying) "■" else "▶", fontSize = 28.sp, color = Color.Black)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("⤢", fontSize = 24.sp, color = Color.White,
                modifier = Modifier.clickable { vm.toggleFullscreen() }.padding(8.dp))
            Text("⚙", fontSize = 24.sp, color = Color.White,
                modifier = Modifier.clickable(onClick = onSettings).padding(8.dp))
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
fun <T> PickerRow(label: String, current: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(90.dp), fontSize = 14.sp)
        Box {
            TextButton(onClick = { open = true }) { Text("$current ▾", color = Orange) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (name, value) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { onPick(value); open = false })
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(vm: MetronomeViewModel, onClose: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshDevices() }
    val panLabel = when {
        kotlin.math.abs(vm.pan) < 0.05f -> "C"
        vm.pan < 0 -> "L${(-vm.pan * 100).toInt()}"
        else -> "R${(vm.pan * 100).toInt()}"
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = Orange) } },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PickerRow(
                    "Sound", vm.sound.label,
                    SoundKind.values().map { it.label to it }
                ) { vm.setSoundKind(it) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pan", Modifier.width(90.dp), fontSize = 14.sp)
                    Slider(
                        value = vm.pan, onValueChange = { vm.updatePan(it) }, valueRange = -1f..1f,
                        colors = SliderDefaults.colors(thumbColor = Orange, activeTrackColor = Orange),
                        modifier = Modifier.weight(1f)
                    )
                    Text(panLabel, Modifier.width(36.dp), textAlign = TextAlign.End, fontSize = 13.sp)
                }

                PickerRow(
                    "Output",
                    if (vm.deviceName.isEmpty()) "System default" else vm.deviceName,
                    listOf("System default" to "") + vm.devices.map { vm.deviceLabel(it) to vm.deviceLabel(it) }
                ) { vm.setOutputDevice(it) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep screen on while playing", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = vm.keepScreenOn, onCheckedChange = { vm.updateKeepScreenOn(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Click light", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = vm.lightEnabled, onCheckedChange = { vm.toggleLight() })
                }
            }
        }
    )
}
