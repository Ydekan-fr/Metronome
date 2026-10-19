package com.ydeclik.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

// ------------------------------------------------------------------ éléments communs

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

/**
 * Nombre modifiable : on le fait glisser verticalement, ou on le touche pour le saisir.
 * À la saisie, le clavier numérique s'ouvre et le curseur se place à droite de la valeur existante.
 */
@OptIn(ExperimentalComposeUiApi::class)
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
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var hadFocus by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val initialTrigger = remember { trigger }

    fun clamp(v: Int) = v.coerceIn(range.first, range.last)
    fun begin() {
        val t = abs(curValue).toString()
        field = TextFieldValue(t, TextRange(t.length))   // curseur à droite
        hadFocus = false
        editing = true
        curEditing(true)
    }
    fun commit() {
        if (!editing) return
        editing = false
        curEditing(false)
        field.text.trim().toIntOrNull()?.let { curOnChange(clamp(if (curValue < 0) -it else it)) }
    }

    LaunchedEffect(trigger) { if (trigger != initialTrigger) begin() }

    Box(Modifier.width(width), contentAlignment = Alignment.Center) {
        if (editing) {
            BasicTextField(
                value = field,
                onValueChange = { v -> field = v.copy(text = v.text.filter { it.isDigit() }) },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White, fontSize = fontSize, fontWeight = weight, textAlign = TextAlign.Center
                ),
                cursorBrush = SolidColor(Orange),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(); keyboard?.hide() }),
                modifier = Modifier
                    .focusRequester(focus)
                    .onFocusChanged { st ->
                        if (st.isFocused) hadFocus = true
                        else if (hadFocus) commit()
                    }
            )
            LaunchedEffect(Unit) {
                runCatching { focus.requestFocus() }
                keyboard?.show()
            }
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

/**
 * Bouton à répétition : un appui change la valeur de 1 ([onPress]) ;
 * un appui long fait ensuite varier la valeur par multiples de 5 ([onHold]).
 */
@Composable
fun HoldBox(
    onPress: () -> Unit,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val curPress by rememberUpdatedState(onPress)
    val curHold by rememberUpdatedState(onHold)
    val scope = rememberCoroutineScope()
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                curPress()
                val job = scope.launch {
                    delay(450)
                    while (true) { curHold(); delay(140) }
                }
                waitForUpOrCancellation()
                job.cancel()
            }
        },
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Icône de note : 1, 2, 3 ou 4 notes, qui s'adapte à la taille disponible. */
@Composable
fun NoteIcon(n: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val slot = size.width / (n + 1)
        val hw = min(slot * 0.9f, 10.dp.toPx())
        val hh = hw * 0.75f
        val lw = max(1f, hw * 0.18f)
        val headY = size.height - hh
        val topY = if (n == 3) size.height * 0.36f else size.height * 0.16f
        val stems = ArrayList<Float>()
        for (i in 0 until n) {
            val cx = size.width * (i + 1) / (n + 1)
            drawOval(color, topLeft = Offset(cx - hw / 2, headY - hh / 2), size = Size(hw, hh))
            val sx = cx + hw / 2 - lw / 2
            stems.add(sx)
            drawLine(color, Offset(sx, headY), Offset(sx, topY), strokeWidth = lw)
        }
        if (n >= 2) {
            val f = stems.first()
            val l = stems.last()
            val bh = max(2f, hw * 0.32f)
            drawRect(color, Offset(f - lw / 2, topY), Size(l - f + lw, bh))
            if (n == 4) drawRect(color, Offset(f - lw / 2, topY + bh * 1.7f), Size(l - f + lw, bh))
        }
    }
}

@Composable
fun MiniButton(symbol: String, on: Boolean, tint: Color, w: Dp, h: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = w, height = h)
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) tint else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol, fontSize = (w.value * 0.55f).coerceIn(6f, 11f).sp, fontWeight = FontWeight.Bold,
            color = if (on) Color.Black else Color.White.copy(alpha = 0.6f)
        )
    }
}

// ------------------------------------------------------------------ écran métronome

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
        HoldBox({ vm.nudge(1) }, { vm.step5(1) }, Modifier.size(width = 140.dp, height = 48.dp)) {
            Text("▲", fontSize = 26.sp, color = Color.White)
        }
        ScrubNumber(
            value = vm.cfg.bpm, onChange = { vm.setBPM(it) }, range = 20..300,
            fontSize = 96.sp, width = 260.dp, weight = FontWeight.Light, pxPerStep = 12f,
            trigger = vm.tempoEditTrigger, onEditing = { vm.typing = it }
        )
        HoldBox({ vm.nudge(-1) }, { vm.step5(-1) }, Modifier.size(width = 140.dp, height = 48.dp)) {
            Text("▼", fontSize = 26.sp, color = Color.White)
        }
        Text("BPM · ${tempoName(vm.cfg.bpm)}", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BeatColumn(vm: MetronomeViewModel, b: Int, colW: Dp) {
    val cfg = vm.cfg
    val subs = cfg.subs.getOrElse(b) { 1 }.coerceAtLeast(1)
    val current = vm.currentBeat == b
    val pad = (colW * 0.06f).coerceAtMost(6.dp)
    val inner = (colW - pad * 2).coerceAtLeast(8.dp)
    val iconH = (inner * 1.2f).coerceIn(28.dp, 52.dp)
    val gap = 2.dp
    val btnW = ((inner - gap * (subs - 1)) / subs).coerceAtMost(24.dp).coerceAtLeast(3.dp)
    val btnH = btnW.coerceIn(14.dp, 22.dp)
    val isStart = b in cfg.sig.groupStarts(cfg.grouping)
    val small = (colW.value * 0.3f).coerceIn(8f, 12f).sp

    Column(
        Modifier
            .width(colW)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = if (current) 0.10f else 0.04f))
            .padding(pad),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Toucher : changer la subdivision · Appui long : accentuer la première subdivision
        Box(
            Modifier
                .fillMaxWidth()
                .height(iconH)
                .combinedClickable(
                    onClick = { vm.cycleSub(b) },
                    onLongClick = { vm.toggleNote(b, 0, NoteState.ACCENT) }
                )
        ) {
            NoteIcon(subs, if (current) Orange else Color.White.copy(alpha = 0.92f), Modifier.fillMaxSize())
            if (subs == 3) {
                Text(
                    "3", fontSize = small, fontWeight = FontWeight.Bold,
                    color = if (current) Orange else Color.White,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (k in 0 until subs) {
                val st = cfg.state(b, k)
                val live = current && vm.currentSub == k
                Column(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (live) Orange.copy(alpha = 0.25f) else Color.Transparent),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    MiniButton(">", st == NoteState.ACCENT, Orange, btnW, btnH) {
                        vm.toggleNote(b, k, NoteState.ACCENT)
                    }
                    MiniButton("‖", st == NoteState.REST, Color.Gray, btnW, btnH) {
                        vm.toggleNote(b, k, NoteState.REST)
                    }
                }
            }
        }
        Text(
            "${b + 1}", fontSize = small,
            fontWeight = if (isStart) FontWeight.Bold else FontWeight.Normal,
            color = if (isStart) Orange else Color.White.copy(alpha = 0.5f)
        )
    }
}

/** Toutes les pulsations sont visibles : plus il y en a, plus elles sont petites. */
@Composable
fun BeatsRow(vm: MetronomeViewModel) {
    val n = vm.cfg.sig.beats
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val spacing = if (n > 8) 3.dp else 6.dp
        val colW = ((maxWidth - spacing * (n - 1)) / n).coerceAtMost(88.dp)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)
        ) {
            for (b in 0 until n) BeatColumn(vm, b, colW)
        }
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
            Pill("SILENCE") { vm.toggleAutoMode() }
            Tag("jouer")
            ScrubNumber(c.playBars, { vm.setPlayBars(it) }, 1..99, 15.sp, 36.dp, pxPerStep = 28f,
                onEditing = { vm.typing = it })
            Tag("mes. ·")
            Tag(if (c.muteRandom) "silence 1 à" else "silence")
            ScrubNumber(c.muteBars, { vm.setMuteBars(it) }, 1..99, 15.sp, 36.dp, pxPerStep = 28f,
                onEditing = { vm.typing = it })
            Tag("mes.")
            Pill("ALÉAT.", on = c.muteRandom) { vm.toggleMuteRandom() }
        } else {
            Pill("AJOUT") { vm.toggleAutoMode() }
            Pill("±") { vm.setAddBpm(-c.addBpm) }
            ScrubNumber(c.addBpm, { vm.setAddBpm(it) }, -50..50, 15.sp, 44.dp, pxPerStep = 28f, signed = true,
                onEditing = { vm.typing = it })
            Tag("BPM toutes les")
            ScrubNumber(c.addEvery, { vm.setAddEvery(it) }, 1..999, 15.sp, 44.dp, pxPerStep = 28f,
                onEditing = { vm.typing = it })
            Pill(if (c.addUnit == AddUnit.BAR) "MES." else "SEC") { vm.toggleAddUnit() }
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
                        .size(width = 32.dp, height = 38.dp)
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
            "Toucher : charger · Appui long : enregistrer",
            color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp
        )
    }
}

@Composable
fun Transport(vm: MetronomeViewModel, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Orange)
                .clickable { vm.toggle() },
            contentAlignment = Alignment.Center
        ) {
            Text(if (vm.isPlaying) "■" else "▶", fontSize = 30.sp, color = Color.Black)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Text(
                "⚙", fontSize = 26.sp, color = Color.White,
                modifier = Modifier.clickable(onClick = onSettings).padding(10.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ réglages

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
        abs(vm.pan) < 0.05f -> "C"
        vm.pan < 0 -> "G${(-vm.pan * 100).toInt()}"
        else -> "D${(vm.pan * 100).toInt()}"
    }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer", color = Orange) } },
        title = { Text("Réglages") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PickerRow(
                    "Son", vm.sound.label,
                    SoundKind.values().map { it.label to it }
                ) { vm.setSoundKind(it) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Balance", Modifier.width(90.dp), fontSize = 14.sp)
                    Slider(
                        value = vm.pan, onValueChange = { vm.updatePan(it) }, valueRange = -1f..1f,
                        colors = SliderDefaults.colors(thumbColor = Orange, activeTrackColor = Orange),
                        modifier = Modifier.weight(1f)
                    )
                    Text(panLabel, Modifier.width(36.dp), textAlign = TextAlign.End, fontSize = 13.sp)
                }

                PickerRow(
                    "Sortie",
                    if (vm.deviceName.isEmpty()) "Par défaut" else vm.deviceName,
                    listOf("Par défaut" to "") + vm.devices.map { vm.deviceLabel(it) to vm.deviceLabel(it) }
                ) { vm.setOutputDevice(it) }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Lumière du clic", Modifier.weight(1f), fontSize = 14.sp)
                    Switch(checked = vm.lightEnabled, onCheckedChange = { vm.toggleLight() })
                }
                Text(
                    "Le volume se règle avec les touches de volume du téléphone.",
                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f)
                )
            }
        }
    )
}
