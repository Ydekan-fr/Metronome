package com.ydeclik.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlin.math.min

// ------------------------------------------------------------------ root

@Composable
fun YdeclikApp(vm: MetronomeViewModel, ex: ExerciseViewModel) {
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

    BackHandler(enabled = ex.stack.size > 1) { ex.back() }
    var showSettings by remember { mutableStateOf(false) }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (ex.screen) {
                Screen.METRONOME -> MetronomeScreen(vm, ex) { showSettings = true }
                Screen.EXERCISES -> ExercisesScreen(ex)
                Screen.ROUTINES -> RoutinesScreen(ex)
                Screen.ROUTINE_EDIT -> RoutineEditScreen(ex)
                Screen.VIEWER -> ViewerScreen(vm, ex)
            }
        }
    }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
}

@Composable
fun MetronomeScreen(vm: MetronomeViewModel, ex: ExerciseViewModel, onSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TabBar(ex)
        TopBar(vm)
        TempoSection(vm)
        BeatsRow(vm)
        AutoPanel(vm)
        PresetsRow(vm)
        Transport(vm, onSettings)
    }
}

@Composable
fun TabBar(ex: ExerciseViewModel) {
    @Composable
    fun Tab(label: String, target: Screen, modifier: Modifier) {
        val on = ex.screen == target
        Box(
            modifier
                .clip(RoundedCornerShape(50))
                .background(if (on) Orange else Color.White.copy(alpha = 0.12f))
                .clickable { ex.tab(target) }
                .padding(vertical = 9.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = if (on) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tab("Metronome", Screen.METRONOME, Modifier.weight(1f))
        Tab("Exercises", Screen.EXERCISES, Modifier.weight(1f))
        Tab("Routines", Screen.ROUTINES, Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------ small shared pieces

@Composable
fun Thumb(e: Exercise, modifier: Modifier) {
    val cr = LocalContext.current.contentResolver
    val bmp by produceState<Bitmap?>(null, e.uri) {
        value = withContext(Dispatchers.IO) { Pics.thumb(cr, e) }
    }
    Box(modifier.background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(
                b.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, alignment = Alignment.TopCenter
            )
        } else {
            Text(if (e.isPdf) "PDF" else "…", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }
    }
}

@Composable
fun TextPrompt(
    title: String, initial: String, numeric: Boolean = false,
    onDone: (String) -> Unit, onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = if (numeric) it.filter(Char::isDigit) else it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text)
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("OK", color = Orange) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

fun hasMediaPermission(ctx: Context): Boolean =
    mediaPermissions().any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

// ------------------------------------------------------------------ exercises gallery

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExerciseCard(e: Exercise, s: ExStats?, onClick: () -> Unit, onLong: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Thumb(e, Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(8.dp)))
        Text(e.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Opened: ${fmtDate(s?.lastOpened ?: 0L)}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
        Text(
            "First ${s?.firstBpm ?: "—"} · Last ${s?.lastBpm ?: "—"} BPM",
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Orange
        )
    }
}

@Composable
fun ExercisesScreen(ex: ExerciseViewModel) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) ex.addImported(uris)
    }
    var showAlbums by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ex.refreshAlbums()
        ex.refreshAlbumItems()
        showAlbums = true
    }
    var menuFor by remember { mutableStateOf<Exercise?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
        TabBar(ex)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("+ Files") { picker.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) }
            Pill("Album: ${ex.album?.name?.ifBlank { "?" } ?: "none"}") {
                if (hasMediaPermission(ctx)) {
                    ex.refreshAlbums(); showAlbums = true
                } else {
                    permLauncher.launch(mediaPermissions())
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Load the last BPM when opening an exercise", Modifier.weight(1f), fontSize = 13.sp)
            Switch(checked = ex.autoApplyBpm, onCheckedChange = { ex.updateAutoApply(it) })
        }

        val lib = ex.library
        if (lib.isEmpty()) {
            Text(
                "No exercise yet.\nTap “+ Files” to add JPG, PNG or PDF files, or choose a photo album.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
                modifier = Modifier.padding(top = 24.dp)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(lib, key = { it.uri }) { e ->
                    ExerciseCard(e, ex.stats[e.uri], onClick = { ex.openExercise(e) }, onLong = { menuFor = e })
                }
            }
        }
    }

    menuFor?.let { e ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("Opened: ${fmtDate(ex.stats[e.uri]?.lastOpened ?: 0L)}") },
            confirmButton = {
                TextButton(onClick = { ex.resetStats(e.uri); menuFor = null }) { Text("Reset stats", color = Orange) }
            },
            dismissButton = {
                Row {
                    if (!e.fromAlbum) {
                        TextButton(onClick = { ex.removeImported(e.uri); menuFor = null }) { Text("Remove") }
                    }
                    TextButton(onClick = { menuFor = null }) { Text("Cancel") }
                }
            }
        )
    }

    if (showAlbums) {
        AlertDialog(
            onDismissRequest = { showAlbums = false },
            title = { Text("Choose an album") },
            text = {
                LazyColumn {
                    item {
                        Text(
                            "None", Modifier.fillMaxWidth().clickable { ex.chooseAlbum(null); showAlbums = false }
                                .padding(vertical = 12.dp),
                            fontSize = 15.sp
                        )
                    }
                    items(ex.albums, key = { it.id }) { a ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { ex.chooseAlbum(a); showAlbums = false }
                                .padding(vertical = 12.dp)
                        ) {
                            Text(a.name, Modifier.weight(1f), fontSize = 15.sp, color = if (ex.album?.id == a.id) Orange else Color.White)
                            Text("${a.count}", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAlbums = false }) { Text("Close", color = Orange) } }
        )
    }
}

// ------------------------------------------------------------------ routines

@Composable
fun RoutinesScreen(ex: ExerciseViewModel) {
    var showNew by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TabBar(ex)
        Row { Pill("+ New routine") { showNew = true } }
        if (ex.routines.isEmpty()) {
            Text(
                "A routine is an editable playlist of exercises — your training program.\nCreate one, add exercises, then swipe from one to the next.",
                color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(ex.routines, key = { it.id }) { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${r.items.size} exercise(s)", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
                    }
                    Pill("Edit") { ex.editRoutine(r.id) }
                    Pill("▶ Start", on = true) { ex.openRoutine(r.id) }
                }
            }
        }
    }
    if (showNew) {
        TextPrompt("New routine", "", onDone = { name ->
            showNew = false
            ex.editRoutine(ex.newRoutine(name))
        }, onDismiss = { showNew = false })
    }
}

@Composable
fun RoutineEditScreen(ex: ExerciseViewModel) {
    val r = ex.routines.firstOrNull { it.id == ex.editingRoutineId }
    if (r == null) {
        LaunchedEffect(Unit) { ex.back() }
        return
    }
    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    val lib = ex.library

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("←", fontSize = 26.sp, modifier = Modifier.clickable { ex.back() }.padding(8.dp))
            Text(
                r.name, Modifier.weight(1f).clickable { showRename = true },
                fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text("🗑", fontSize = 20.sp, modifier = Modifier.clickable { showDelete = true }.padding(8.dp))
            Pill("▶ Start", on = true) { ex.openRoutine(r.id) }
        }
        Row { Pill("+ Add exercises") { showAdd = true } }
        if (r.items.isEmpty()) {
            Text("This routine is empty.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(r.items) { idx, uri ->
                val e = lib.firstOrNull { it.uri == uri }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("${idx + 1}", Modifier.width(22.dp), color = Orange, fontWeight = FontWeight.Bold)
                    if (e != null) Thumb(e, Modifier.size(width = 40.dp, height = 52.dp).clip(RoundedCornerShape(6.dp)))
                    else Box(Modifier.size(width = 40.dp, height = 52.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e?.name ?: "Missing file", fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (e == null) Color.White.copy(alpha = 0.4f) else Color.White)
                        val s = e?.let { ex.stats[it.uri] }
                        if (s?.lastBpm != null) {
                            Text("Last ${s.lastBpm} BPM", fontSize = 11.sp, color = Orange)
                        }
                    }
                    Text("↑", fontSize = 20.sp, modifier = Modifier.clickable { ex.moveInRoutine(r.id, idx, -1) }.padding(8.dp))
                    Text("↓", fontSize = 20.sp, modifier = Modifier.clickable { ex.moveInRoutine(r.id, idx, 1) }.padding(8.dp))
                    Text("✕", fontSize = 18.sp, modifier = Modifier.clickable { ex.removeFromRoutine(r.id, idx) }.padding(8.dp))
                }
            }
        }
    }

    if (showRename) {
        TextPrompt("Rename routine", r.name, onDone = { ex.renameRoutine(r.id, it); showRename = false },
            onDismiss = { showRename = false })
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete “${r.name}”?") },
            text = { Text("The exercise files themselves are not deleted.") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; ex.deleteRoutine(r.id); ex.back() }) {
                    Text("Delete", color = Orange)
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
        )
    }
    if (showAdd) {
        val selected = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add exercises") },
            text = {
                if (lib.isEmpty()) {
                    Text("Your library is empty. Add files in the Exercises tab first.")
                } else {
                    LazyColumn {
                        items(lib, key = { it.uri }) { e ->
                            val on = e.uri in selected
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { if (on) selected.remove(e.uri) else selected.add(e.uri) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(checked = on, onCheckedChange = { if (it) selected.add(e.uri) else selected.remove(e.uri) })
                                Thumb(e, Modifier.size(width = 36.dp, height = 46.dp).clip(RoundedCornerShape(5.dp)))
                                Text(e.name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ex.addToRoutine(r.id, selected.toList()); showAdd = false }) {
                    Text("Add (${selected.size})", color = Orange)
                }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
}

// ------------------------------------------------------------------ viewer

@Composable
fun ZoomableImage(bitmap: ImageBitmap, onZoomed: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val notify by rememberUpdatedState(onZoomed)

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // Only take over the gesture for pinch, or when already zoomed in;
                        // otherwise a one-finger swipe is left to the pager.
                        if (event.changes.size > 1 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val ns = (scale * zoom).coerceIn(1f, 5f)
                            val maxX = (ns - 1f) * size.width / 2f
                            val maxY = (ns - 1f) * size.height / 2f
                            offset = if (ns <= 1f) Offset.Zero else Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                            scale = ns
                            notify(ns > 1f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = if (scale > 1f) 1f else 2.5f
                    offset = Offset.Zero
                    notify(scale > 1f)
                })
            }
    ) {
        Image(
            bitmap, null,
            Modifier
                .fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
fun ImageViewer(uri: String, onZoomed: (Boolean) -> Unit) {
    val cr = LocalContext.current.contentResolver
    val res by produceState<Pair<Boolean, Bitmap?>>(false to null, uri) {
        value = true to withContext(Dispatchers.IO) { runCatching { Pics.decode(cr, Uri.parse(uri), 1600) }.getOrNull() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0E0E10)), contentAlignment = Alignment.Center) {
        val b = res.second
        when {
            !res.first -> Text("Loading…", color = Color.White.copy(alpha = 0.5f))
            b == null -> Text("Cannot open this file", color = Color.White.copy(alpha = 0.5f))
            else -> ZoomableImage(b.asImageBitmap(), onZoomed)
        }
    }
}

@Composable
fun PdfPage(doc: PdfDoc, index: Int, widthPx: Int) {
    val bmp by produceState<Bitmap?>(null, doc, index) {
        value = withContext(Dispatchers.IO) { runCatching { doc.render(index, widthPx) }.getOrNull() }
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    } else {
        Box(Modifier.fillMaxWidth().height(400.dp).background(Color.White.copy(alpha = 0.05f)))
    }
}

@Composable
fun PdfViewer(uri: String) {
    val cr = LocalContext.current.contentResolver
    val docState = produceState<PdfDoc?>(null, uri) {
        val d = withContext(Dispatchers.IO) { runCatching { PdfDoc(cr, Uri.parse(uri)) }.getOrNull() }
        value = d
        try { awaitCancellation() } finally { d?.close() }
    }
    val doc = docState.value
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0E0E10))) {
        val widthPx = min(with(LocalDensity.current) { maxWidth.roundToPx() }, 2000)
        if (doc == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Loading…", color = Color.White.copy(alpha = 0.5f))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(doc.pageCount) { i -> PdfPage(doc, i, widthPx) }
            }
        }
    }
}

@Composable
fun MiniMetronome(vm: MetronomeViewModel, s: ExStats?, onTarget: () -> Unit) {
    val bpm = vm.cfg.bpm
    val first = s?.firstBpm
    val last = s?.lastBpm
    val target = s?.targetBpm

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1F1F22))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // --- Progress
        if (first == null) {
            Tag("PROGRESS · press start to record the first BPM")
        } else {
            val delta = bpm - first
            val pct = if (first > 0) delta * 100 / first else 0
            val col = when {
                delta > 0 -> Color(0xFF34C759)
                delta < 0 -> Color(0xFFFF453A)
                else -> Color.White.copy(alpha = 0.6f)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Progress: ${if (delta > 0) "+" else ""}$delta BPM (${if (pct > 0) "+" else ""}$pct %)",
                    color = col, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                )
                if (target != null) Tag("$bpm / $target")
            }
            if (target != null && target != first) {
                val p = ((bpm - first).toFloat() / (target - first)).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { p },
                    color = Orange,
                    trackColor = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                )
            }
        }

        // --- First / Last / Target
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Tag("FIRST")
                Text(first?.toString() ?: "—", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(enabled = last != null) { last?.let { vm.setBPM(it) } }
            ) {
                Tag("LAST (tap to use)")
                Text(last?.toString() ?: "—", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Orange)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onTarget)) {
                Tag("TARGET (tap)")
                Text(target?.toString() ?: "—", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        // --- Controls
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(14.dp).clip(CircleShape)
                    .background(if (vm.flash) (if (vm.flashAccent) Orange else Color(0xFF34C759)) else Color.White.copy(alpha = 0.1f))
            )
            Spacer(Modifier.weight(1f))
            HoldBox({ vm.nudge(-1) }, Modifier.size(52.dp)) { Text("−", fontSize = 30.sp) }
            ScrubNumber(
                value = bpm, onChange = { vm.setBPM(it) }, range = 20..300,
                fontSize = 38.sp, width = 96.dp, pxPerStep = 12f, onEditing = { vm.typing = it }
            )
            HoldBox({ vm.nudge(1) }, Modifier.size(52.dp)) { Text("+", fontSize = 30.sp) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Orange).clickable { vm.toggle() },
                contentAlignment = Alignment.Center
            ) {
                Text(if (vm.isPlaying) "■" else "▶", fontSize = 24.sp, color = Color.Black)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(vm: MetronomeViewModel, ex: ExerciseViewModel) {
    val items = ex.viewerItems
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { ex.back() }
        return
    }
    val pager = rememberPagerState(initialPage = ex.viewerStart.coerceIn(0, items.lastIndex)) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var showTarget by remember { mutableStateOf(false) }

    val page = pager.settledPage
    val cur = items.getOrElse(page) { items.first() }
    val stats = ex.stats[cur.uri]

    // New exercise shown: remember the opening date and recall the last BPM used.
    LaunchedEffect(page) {
        zoomed = false
        ex.markOpened(cur.uri)
        if (ex.autoApplyBpm) ex.stats[cur.uri]?.lastBpm?.let { vm.setBPM(it) }
    }
    // While playing: record the first and the last BPM used on this exercise.
    LaunchedEffect(vm.cfg.bpm, vm.isPlaying, page) {
        if (vm.isPlaying) ex.recordBpm(cur.uri, vm.cfg.bpm)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("←", fontSize = 26.sp, modifier = Modifier.clickable { ex.back() }.padding(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ex.viewerTitle ?: cur.name, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (ex.viewerTitle != null) {
                    Text(cur.name, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (items.size > 1) {
                Text("${page + 1} / ${items.size}", fontWeight = FontWeight.Bold, color = Orange, modifier = Modifier.padding(8.dp))
            }
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            userScrollEnabled = !zoomed && items.size > 1,
            key = { "${it}_${items[it].uri}" }
        ) { p ->
            val e = items[p]
            if (e.isPdf) PdfViewer(e.uri)
            else ImageViewer(e.uri) { z -> if (p == pager.currentPage) zoomed = z }
        }

        MiniMetronome(vm, stats) { showTarget = true }
    }

    if (showTarget) {
        TextPrompt(
            "Target BPM", stats?.targetBpm?.toString() ?: "", numeric = true,
            onDone = { t ->
                ex.updateTarget(cur.uri, t.toIntOrNull()?.coerceIn(20, 300))
                showTarget = false
            },
            onDismiss = { showTarget = false }
        )
    }
}
