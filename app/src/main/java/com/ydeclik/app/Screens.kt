package com.ydeclik.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------ racine

@Composable
fun YdeclikApp(vm: MetronomeViewModel, ex: ExerciseViewModel, sl: SetlistViewModel) {
    val view = LocalView.current

    // Plein écran du fichier (double toucher) : on masque aussi les barres système.
    LaunchedEffect(ex.immersive) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val c = WindowCompat.getInsetsController(window, view)
        if (ex.immersive) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Rapport du dernier plantage, s'il y en a un.
    val appCtx = LocalContext.current
    var crash by remember { mutableStateOf(appCtx.getSharedPreferences("ydeclik_crash", 0).getString("trace", null)) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    BackHandler(enabled = ex.stack.size > 1) { ex.back() }
    BackHandler(enabled = ex.immersive) { ex.immersive = false }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (ex.screen) {
                Screen.METRONOME -> MetronomeScreen(vm, ex)
                Screen.EXERCISES -> ExercisesScreen(ex)
                Screen.ROUTINES -> RoutinesScreen(ex)
                Screen.ROUTINE_EDIT -> RoutineEditScreen(ex)
                Screen.VIEWER -> ViewerScreen(vm, ex)
                Screen.USAGE -> UsageScreen(ex)
                Screen.SETLISTS -> SetlistsScreen(ex, sl)
                Screen.SETLIST_EDIT -> SetlistEditScreen(vm, ex, sl)
                Screen.SETLIST_LIVE -> SetlistLiveScreen(vm, ex, sl)
            }
        }
    }
    if (ex.settingsOpen) SettingsDialog(vm) { ex.settingsOpen = false }
    crash?.let { trace ->
        val close = {
            appCtx.getSharedPreferences("ydeclik_crash", 0).edit().remove("trace").apply()
            crash = null
        }
        AlertDialog(
            onDismissRequest = close,
            title = { Text("L'application s'est fermée") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Touchez « Copier », puis envoyez ce texte pour que le problème soit corrigé.", fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(trace.take(2500), fontSize = 9.sp, color = Color.White.copy(alpha = 0.7f))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(trace.take(6000)))
                    close()
                }) { Text("Copier", color = Orange) }
            },
            dismissButton = { TextButton(onClick = close) { Text("Fermer") } }
        )
    }
}

@Composable
fun MetronomeScreen(vm: MetronomeViewModel, ex: ExerciseViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HeaderBar(ex, "Métronome")
        TopBar(vm)
        TempoSection(vm)
        BeatsRow(vm)
        AutoPanel(vm)
        PresetsRow(vm)
        Transport(vm)
    }
}

// ------------------------------------------------------------------ éléments partagés

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

/**
 * Boîte de saisie. En mode numérique, le clavier numérique s'ouvre
 * et le curseur se place à droite de la valeur existante.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TextPrompt(
    title: String, initial: String, numeric: Boolean = false,
    onDone: (String) -> Unit, onDismiss: () -> Unit
) {
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { v -> field = if (numeric) v.copy(text = v.text.filter(Char::isDigit)) else v },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onDone(field.text) }),
                modifier = Modifier.focusRequester(focus)
            )
            LaunchedEffect(Unit) {
                runCatching { focus.requestFocus() }
                keyboard?.show()
            }
        },
        confirmButton = { TextButton(onClick = { onDone(field.text) }) { Text("OK", color = Orange) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
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

// ------------------------------------------------------------------ galerie des exercices

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExerciseCard(ex: ExerciseViewModel, e: Exercise, onClick: () -> Unit, onLong: () -> Unit) {
    // Les BPM affichés sont ceux de la page ouverte en dernier (images : une seule page).
    val s = ex.fileStats(e.uri)
    val page = ex.metaPage(e.uri)
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
        Text(
            "Ouvert : ${fmtDate(ex.lastOpened(e.uri))}",
            fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f)
        )
        Text(
            (if (e.isPdf && page > 0) "Page ${page + 1} · " else "") +
                "Premier ${s?.firstBpm ?: "—"} · Dernier ${s?.lastBpm ?: "—"} BPM",
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
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) ex.chooseFolder(u)
    }
    var folderMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp)) {
        HeaderBar(ex, "Exercices")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("+ Fichiers") { picker.launch(arrayOf("image/jpeg", "image/png", "application/pdf")) }
            Pill("Album : ${ex.album?.name?.ifBlank { "?" } ?: "aucun"}") {
                if (hasMediaPermission(ctx)) {
                    ex.refreshAlbums(); showAlbums = true
                } else {
                    permLauncher.launch(mediaPermissions())
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(if (ex.folderUri != null) "Dossier : ${ex.folderName.ifBlank { "?" }}" else "+ Dossier (Dropbox…)", on = ex.folderUri != null) {
                if (ex.folderUri != null) folderMenu = true else folderPicker.launch(null)
            }
            if (ex.folderUri != null) Pill("↻ Actualiser") { ex.refreshFolder() }
            TimerPill(ex)
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recharger le dernier BPM à l'ouverture", Modifier.weight(1f), fontSize = 13.sp)
            Switch(checked = ex.autoApplyBpm, onCheckedChange = { ex.updateAutoApply(it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag("TRI")
            Pill("A–Z", on = ex.sortMode == SortMode.NAME) { ex.updateSort(SortMode.NAME) }
            Pill("Récents", on = ex.sortMode == SortMode.RECENT) { ex.updateSort(SortMode.RECENT) }
        }
        Spacer(Modifier.height(8.dp))

        val lib = when (ex.sortMode) {
            SortMode.NAME -> ex.library.sortedBy { it.name.lowercase() }
            SortMode.RECENT -> ex.library.sortedByDescending { ex.lastOpened(it.uri) }
        }
        if (lib.isEmpty()) {
            Text(
                "Aucun exercice.\nTouchez « + Fichiers » pour ajouter des JPG, PNG ou PDF, ou choisissez un album photo.",
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
                    ExerciseCard(ex, e, onClick = { ex.openExercise(e, lib) }, onLong = { menuFor = e })
                }
            }
        }
    }

    if (folderMenu) {
        AlertDialog(
            onDismissRequest = { folderMenu = false },
            title = { Text("Dossier surveillé") },
            text = { Text("${ex.folderName}\n${ex.folderItems.size} fichier(s) trouvé(s). Il est relu à chaque retour dans l'application.") },
            confirmButton = {
                TextButton(onClick = { folderMenu = false; folderPicker.launch(null) }) { Text("Changer", color = Orange) }
            },
            dismissButton = {
                TextButton(onClick = { folderMenu = false; ex.chooseFolder(null) }) { Text("Retirer") }
            }
        )
    }

    menuFor?.let { e ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("Ouvert : ${fmtDate(ex.lastOpened(e.uri))}") },
            confirmButton = {
                TextButton(onClick = { ex.resetStats(e.uri); menuFor = null }) {
                    Text("Réinitialiser les stats", color = Orange)
                }
            },
            dismissButton = {
                Row {
                    if (!e.fromAlbum) {
                        TextButton(onClick = { ex.removeImported(e.uri); menuFor = null }) { Text("Retirer") }
                    }
                    TextButton(onClick = { menuFor = null }) { Text("Annuler") }
                }
            }
        )
    }

    if (showAlbums) {
        AlertDialog(
            onDismissRequest = { showAlbums = false },
            title = { Text("Choisir un album") },
            text = {
                LazyColumn {
                    item {
                        Text(
                            "Aucun",
                            Modifier.fillMaxWidth().clickable { ex.chooseAlbum(null); showAlbums = false }
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
                            Text(
                                a.name, Modifier.weight(1f), fontSize = 15.sp,
                                color = if (ex.album?.id == a.id) Orange else Color.White
                            )
                            Text("${a.count}", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAlbums = false }) { Text("Fermer", color = Orange) } }
        )
    }
}

// ------------------------------------------------------------------ routines

@Composable
fun RoutinesScreen(ex: ExerciseViewModel) {
    var showNew by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HeaderBar(ex, "Routines")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("+ Nouvelle routine") { showNew = true }
            TimerPill(ex)
        }
        if (ex.routines.isEmpty()) {
            Text(
                "Une routine est une playlist modifiable d'exercices : votre programme d'entraînement.\n" +
                    "Créez-en une, ajoutez des exercices, puis passez de l'un à l'autre en glissant.",
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
                        Text(
                            r.name, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val total = r.items.sumOf { it.minutes }
                        Text(
                            "${r.items.size} exercice(s)" + if (total > 0) " · $total min" else "",
                            fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                    Pill("Éditer") { ex.editRoutine(r.id) }
                    Pill("▶ Lancer", on = true) { ex.openRoutine(r.id) }
                }
            }
        }
    }
    if (showNew) {
        TextPrompt("Nouvelle routine", "", onDone = { name ->
            showNew = false
            ex.editRoutine(ex.newRoutine(name))
        }, onDismiss = { showNew = false })
    }
}
