package com.ydeclik.app

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.min

// ------------------------------------------------------------------ zoom commun (images et PDF)

/**
 * Conteneur zoomable : pincer avec deux doigts change le niveau de zoom (1×, 1,5×, 2×, 3×) ;
 * un double toucher appelle [onDoubleTap] (plein écran). Le contenu reçoit la largeur visible et le zoom.
 */
@Composable
fun ZoomPane(
    onDoubleTap: () -> Unit,
    onZoomed: (Boolean) -> Unit,
    content: @Composable (Dp, Float) -> Unit
) {
    val steps = remember { listOf(1f, 1.5f, 2f, 3f) }
    var stepIdx by remember { mutableIntStateOf(0) }
    val zoom = steps[stepIdx]
    val hScroll = rememberScrollState()
    val curDouble by rememberUpdatedState(onDoubleTap)
    val curZoomed by rememberUpdatedState(onZoomed)

    LaunchedEffect(stepIdx) {
        curZoomed(stepIdx > 0)
        if (stepIdx == 0) hScroll.scrollTo(0)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF0E0E10)).clipToBounds()) {
        val viewW = maxWidth
        Box(
            Modifier
                .fillMaxSize()
                // Le pincement à deux doigts est traité en premier (passe Initial) pour que les listes ne défilent pas.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var acc = 1f
                        do {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            if (ev.changes.size >= 2) {
                                acc *= ev.calculateZoom()
                                if (acc > 1.25f && stepIdx < steps.lastIndex) { stepIdx++; acc = 1f }
                                else if (acc < 0.8f && stepIdx > 0) { stepIdx--; acc = 1f }
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { curDouble() }) }
                .then(if (zoom > 1f) Modifier.horizontalScroll(hScroll) else Modifier)
        ) {
            content(viewW, zoom)
        }
    }
}

// ------------------------------------------------------------------ image (défilement vertical + zoom)

@Composable
fun ImageViewer(uri: String, onDoubleTap: () -> Unit, onZoomed: (Boolean) -> Unit) {
    val cr = LocalContext.current.contentResolver
    val res by produceState<Pair<Boolean, Bitmap?>>(false to null, uri) {
        value = true to withContext(Dispatchers.IO) {
            runCatching { Pics.decode(cr, Uri.parse(uri), 2600) }.getOrNull()
        }
    }
    val b = res.second
    when {
        !res.first -> Box(Modifier.fillMaxSize().background(Color(0xFF0E0E10)), contentAlignment = Alignment.Center) {
            Text("Chargement…", color = Color.White.copy(alpha = 0.5f))
        }
        b == null -> Box(Modifier.fillMaxSize().background(Color(0xFF0E0E10)), contentAlignment = Alignment.Center) {
            Text("Impossible d'ouvrir ce fichier", color = Color.White.copy(alpha = 0.5f))
        }
        else -> ZoomPane(onDoubleTap, onZoomed) { viewW, zoom ->
            // L'image occupe toute la largeur ; si elle est plus haute que l'écran, on la fait défiler.
            Box(
                Modifier
                    .width(viewW * zoom)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter
            ) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
            }
        }
    }
}

// ------------------------------------------------------------------ PDF (défilement, zoom, page courante)

@Composable
fun PdfPage(doc: PdfDoc, index: Int, widthPx: Int) {
    val bmp by produceState<Bitmap?>(null, doc, index, widthPx) {
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
fun PdfViewer(
    uri: String,
    startPage: Int,
    onPage: (Int) -> Unit,
    onDoubleTap: () -> Unit,
    onZoomed: (Boolean) -> Unit
) {
    val cr = LocalContext.current.contentResolver
    val docState = produceState<PdfDoc?>(null, uri) {
        val d = withContext(Dispatchers.IO) { runCatching { PdfDoc(cr, Uri.parse(uri)) }.getOrNull() }
        value = d
        try { awaitCancellation() } finally { d?.close() }
    }
    val doc = docState.value
    val curOnPage by rememberUpdatedState(onPage)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage.coerceAtLeast(0))

    // Page courante = celle qui occupe le centre de l'écran.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.firstOrNull { center >= it.offset && center < it.offset + it.size }?.index
        }.collect { idx -> if (idx != null) curOnPage(idx) }
    }

    if (doc == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0E0E10)), contentAlignment = Alignment.Center) {
            Text("Chargement…", color = Color.White.copy(alpha = 0.5f))
        }
    } else {
        ZoomPane(onDoubleTap, onZoomed) { viewW, zoom ->
            val density = LocalDensity.current
            val viewPx = with(density) { viewW.roundToPx() }
            // Les pages sont rendues à la taille du zoom pour que le texte reste net.
            val widthPx = min((viewPx * zoom).toInt(), 2400)
            LazyColumn(
                Modifier.width(viewW * zoom).fillMaxHeight(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(doc.pageCount) { i -> PdfPage(doc, i, widthPx) }
            }
        }
    }
}

// ------------------------------------------------------------------ minuteur de routine

fun fmtClock(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)

/** Vibration + bip quand le temps d'un exercice est écoulé. */
fun alertTimeUp(ctx: Context) {
    runCatching {
        ctx.getSystemService(Vibrator::class.java)
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300, 150, 300), -1))
    }
    runCatching {
        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        tg.startTone(ToneGenerator.TONE_PROP_ACK, 700)
        Handler(Looper.getMainLooper()).postDelayed({ tg.release() }, 1200)
    }
}

// ------------------------------------------------------------------ petit métronome (compact)

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.White) {
    Column(
        modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 9.sp, color = Color.White.copy(alpha = 0.55f), fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
fun MiniMetronome(vm: MetronomeViewModel, s: ExStats?, onEditFirst: () -> Unit, onEditTarget: () -> Unit) {
    val bpm = vm.cfg.bpm
    val first = s?.firstBpm
    val last = s?.lastBpm
    val target = s?.targetBpm

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1F1F22))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // Premier / dernier / cible (touchez pour modifier) + progression
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatChip("PREMIER", first?.toString() ?: "—", Modifier.clickable(onClick = onEditFirst))
            StatChip(
                "DERNIER", last?.toString() ?: "—",
                Modifier.clickable(enabled = last != null) { last?.let { vm.setBPM(it) } }, Orange
            )
            StatChip("CIBLE", target?.toString() ?: "—", Modifier.clickable(onClick = onEditTarget))
            Spacer(Modifier.weight(1f))
            if (first == null) {
                Text("Appuyez sur ▶", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
            } else {
                val delta = bpm - first
                val pct = if (first > 0) delta * 100 / first else 0
                val col = when {
                    delta > 0 -> Color(0xFF34C759)
                    delta < 0 -> Color(0xFFFF453A)
                    else -> Color.White.copy(alpha = 0.6f)
                }
                Text(
                    "${if (delta > 0) "+" else ""}$delta BPM (${if (pct > 0) "+" else ""}$pct %)",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = col
                )
            }
        }
        if (target != null && first != null && target != first) {
            val p = ((bpm - first).toFloat() / (target - first)).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { p },
                color = Orange,
                trackColor = Color.White.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
            )
        }

        // Commandes
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(12.dp).clip(CircleShape)
                    .background(
                        if (vm.flash) (if (vm.flashAccent) Orange else Color(0xFF34C759))
                        else Color.White.copy(alpha = 0.1f)
                    )
            )
            Spacer(Modifier.weight(1f))
            HoldBox({ vm.nudge(-1) }, { vm.step5(-1) }, Modifier.size(44.dp)) { Text("−", fontSize = 26.sp) }
            ScrubNumber(
                value = bpm, onChange = { vm.setBPM(it) }, range = 20..300,
                fontSize = 30.sp, width = 80.dp, pxPerStep = 12f, onEditing = { vm.typing = it }
            )
            HoldBox({ vm.nudge(1) }, { vm.step5(1) }, Modifier.size(44.dp)) { Text("+", fontSize = 26.sp) }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Orange).clickable { vm.toggle() },
                contentAlignment = Alignment.Center
            ) {
                Text(if (vm.isPlaying) "■" else "▶", fontSize = 20.sp, color = Color.Black)
            }
        }
    }
}

// ------------------------------------------------------------------ visionneuse

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ViewerScreen(vm: MetronomeViewModel, ex: ExerciseViewModel) {
    val items = ex.viewerItems
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { ex.back() }
        return
    }
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = ex.viewerStart.coerceIn(0, items.lastIndex)) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var showTarget by remember { mutableStateOf(false) }
    var showFirst by remember { mutableStateOf(false) }
    val pdfPages = remember { mutableStateMapOf<Int, Int>() }

    val page = pager.settledPage
    val cur = items.getOrElse(page) { items.first() }
    // Pour un PDF, chaque page a ses propres BPM (clé « fichier#pN »).
    val pdfPage = if (cur.isPdf) (pdfPages[page] ?: ex.metaPage(cur.uri)) else 0
    val key = statKey(cur.uri, pdfPage)
    val stats = ex.stats[key]

    // Minuteur de routine (minutes réglées par exercice) : il ne décompte que si le métronome joue.
    val minutes = ex.viewerMinutes.getOrElse(page) { 0 }
    var remaining by remember(page) { mutableIntStateOf(minutes * 60) }
    var finished by remember(page) { mutableStateOf(false) }

    // Nouvel exercice affiché : on note la date d'ouverture.
    LaunchedEffect(page) {
        zoomed = false
        ex.markOpened(cur.uri)
    }
    // Nouvelle page / nouvel exercice : on rappelle le dernier BPM utilisé, puis on autorise l'enregistrement.
    var readyKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        if (ex.autoApplyBpm) ex.stats[key]?.lastBpm?.let { vm.setBPM(it) }
        readyKey = key
    }
    // Pendant la lecture : premier et dernier BPM utilisés sur cette page.
    LaunchedEffect(vm.cfg.bpm, vm.isPlaying, key, readyKey) {
        if (vm.isPlaying && readyKey == key) ex.recordBpm(key, vm.cfg.bpm)
    }
    // Mémorise la page de PDF ouverte en dernier (référence de l'écran d'accueil).
    LaunchedEffect(page, pdfPage) {
        if (cur.isPdf) ex.setLastPage(cur.uri, pdfPage)
    }
    // Décompte.
    LaunchedEffect(page, vm.isPlaying) {
        if (!vm.isPlaying || minutes <= 0 || remaining <= 0) return@LaunchedEffect
        while (remaining > 0) {
            delay(1000)
            remaining--
        }
        finished = true
        alertTimeUp(ctx)
        if (ex.viewerAutoAdvance && page < items.lastIndex) pager.animateScrollToPage(page + 1)
    }

    val immersive = ex.immersive

    Column(Modifier.fillMaxSize()) {
        if (!immersive) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("←", fontSize = 26.sp, modifier = Modifier.clickable { ex.back() }.padding(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        ex.viewerTitle ?: cur.name, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    val sub = (if (ex.viewerTitle != null) cur.name else "") +
                        (if (cur.isPdf) (if (ex.viewerTitle != null) " · " else "") + "page ${pdfPage + 1}" else "")
                    if (sub.isNotEmpty()) {
                        Text(
                            sub, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (minutes > 0) {
                    Text(
                        "⏱ ${fmtClock(remaining)}" + if (finished) " ✓" else "",
                        fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        color = when {
                            finished -> Color(0xFFFF453A)
                            vm.isPlaying -> Orange
                            else -> Color.White
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable { remaining = minutes * 60; finished = false }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                if (items.size > 1) {
                    Text(
                        "${page + 1} / ${items.size}", fontWeight = FontWeight.Bold, color = Orange,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            userScrollEnabled = !zoomed && items.size > 1,
            key = { "${it}_${items[it].uri}" }
        ) { p ->
            val e = items[p]
            val toggleFull = { ex.immersive = !ex.immersive }
            if (e.isPdf) {
                PdfViewer(
                    uri = e.uri,
                    startPage = pdfPages[p] ?: ex.metaPage(e.uri),
                    onPage = { i -> if (p == pager.currentPage) pdfPages[p] = i },
                    onDoubleTap = toggleFull,
                    onZoomed = { z -> if (p == pager.currentPage) zoomed = z }
                )
            } else {
                ImageViewer(e.uri, toggleFull) { z -> if (p == pager.currentPage) zoomed = z }
            }
        }

        if (!immersive) {
            MiniMetronome(vm, stats, onEditFirst = { showFirst = true }, onEditTarget = { showTarget = true })
        }
    }

    if (showFirst) {
        TextPrompt(
            "BPM de départ", stats?.firstBpm?.toString() ?: vm.cfg.bpm.toString(), numeric = true,
            onDone = { t ->
                t.toIntOrNull()?.let { ex.updateFirstBpm(key, it.coerceIn(20, 300)) }
                showFirst = false
            },
            onDismiss = { showFirst = false }
        )
    }
    if (showTarget) {
        TextPrompt(
            "BPM cible", stats?.targetBpm?.toString() ?: "", numeric = true,
            onDone = { t ->
                ex.updateTarget(key, t.toIntOrNull()?.coerceIn(20, 300))
                showTarget = false
            },
            onDismiss = { showTarget = false }
        )
    }
}

// ------------------------------------------------------------------ éditeur de routine

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
    var minutesFor by remember { mutableStateOf<Int?>(null) }
    val lib = ex.library
    val total = r.items.sumOf { it.minutes }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("←", fontSize = 26.sp, modifier = Modifier.clickable { ex.back() }.padding(8.dp))
            Text(
                r.name, Modifier.weight(1f).clickable { showRename = true },
                fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text("🗑", fontSize = 20.sp, modifier = Modifier.clickable { showDelete = true }.padding(8.dp))
            Pill("▶ Lancer", on = true) { ex.openRoutine(r.id) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("+ Ajouter des exercices") { showAdd = true }
            Text(
                if (total > 0) "Total : $total min" else "Pas de minuteur",
                Modifier.weight(1f).padding(start = 12.dp),
                fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Passer à l'exercice suivant quand le minuteur se termine", Modifier.weight(1f), fontSize = 13.sp)
            Switch(checked = r.autoAdvance, onCheckedChange = { ex.updateAutoAdvance(r.id, it) })
        }
        if (r.items.isEmpty()) {
            Text("Cette routine est vide.", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(r.items) { idx, ri ->
                val e = lib.firstOrNull { it.uri == ri.uri }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("${idx + 1}", Modifier.width(20.dp), color = Orange, fontWeight = FontWeight.Bold)
                    if (e != null) Thumb(e, Modifier.size(width = 40.dp, height = 52.dp).clip(RoundedCornerShape(6.dp)))
                    else Box(Modifier.size(width = 40.dp, height = 52.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            e?.name ?: "Fichier introuvable", fontSize = 14.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (e == null) Color.White.copy(alpha = 0.4f) else Color.White
                        )
                        val last = e?.let { ex.fileStats(it.uri)?.lastBpm }
                        if (last != null) Text("Dernier : $last BPM", fontSize = 11.sp, color = Orange)
                        Text(
                            "⏱ " + if (ri.minutes > 0) "${ri.minutes} min" else "sans minuteur",
                            fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { minutesFor = idx }
                                .padding(vertical = 3.dp, horizontal = 2.dp)
                        )
                    }
                    Text("↑", fontSize = 20.sp, modifier = Modifier.clickable { ex.moveInRoutine(r.id, idx, -1) }.padding(6.dp))
                    Text("↓", fontSize = 20.sp, modifier = Modifier.clickable { ex.moveInRoutine(r.id, idx, 1) }.padding(6.dp))
                    Text("✕", fontSize = 18.sp, modifier = Modifier.clickable { ex.removeFromRoutine(r.id, idx) }.padding(6.dp))
                }
            }
        }
    }

    minutesFor?.let { idx ->
        TextPrompt(
            "Minuteur en minutes (0 = aucun)", (r.items.getOrNull(idx)?.minutes ?: 0).toString(), numeric = true,
            onDone = { ex.updateItemMinutes(r.id, idx, it.toIntOrNull() ?: 0); minutesFor = null },
            onDismiss = { minutesFor = null }
        )
    }
    if (showRename) {
        TextPrompt("Renommer la routine", r.name, onDone = { ex.renameRoutine(r.id, it); showRename = false },
            onDismiss = { showRename = false })
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Supprimer « ${r.name} » ?") },
            text = { Text("Les fichiers d'exercices ne sont pas supprimés.") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; ex.deleteRoutine(r.id); ex.back() }) {
                    Text("Supprimer", color = Orange)
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Annuler") } }
        )
    }
    if (showAdd) {
        val selected = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Ajouter des exercices") },
            text = {
                if (lib.isEmpty()) {
                    Text("Votre bibliothèque est vide. Ajoutez d'abord des fichiers dans l'onglet Exercices.")
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
                                Checkbox(
                                    checked = on,
                                    onCheckedChange = { if (it) selected.add(e.uri) else selected.remove(e.uri) }
                                )
                                Thumb(e, Modifier.size(width = 36.dp, height = 46.dp).clip(RoundedCornerShape(5.dp)))
                                Text(e.name, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { ex.addToRoutine(r.id, selected.toList()); showAdd = false }) {
                    Text("Ajouter (${selected.size})", color = Orange)
                }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Annuler") } }
        )
    }
}
