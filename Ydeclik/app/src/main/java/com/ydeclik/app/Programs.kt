package com.ydeclik.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProgramsScreen(ex: ExerciseViewModel) {
    var showNew by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HeaderBar(ex, "Programmes")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("+ Nouveau programme") { showNew = true }
            TimerPill(ex)
        }
        if (ex.programs.isEmpty()) {
            Text(
                "Un programme est une liste de liens : un fichier (avec les pages voulues d'un PDF) ou une routine existante.\n" +
                    "Créez-en un, ajoutez des lignes, puis glissez d'une page à l'autre.",
                color = Pal.fg.copy(alpha = 0.6f), fontSize = 14.sp
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(ex.programs, key = { it.id }) { p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Pal.fg.copy(alpha = 0.06f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text("${p.lines.size} ligne(s)", fontSize = 12.sp, color = Pal.fg.copy(alpha = 0.6f))
                    }
                    Pill("Éditer") { ex.editProgram(p.id) }
                    Pill("▶ Lancer", on = true) { ex.openProgram(p.id) }
                }
            }
        }
    }
    if (showNew) {
        TextPrompt("Nouveau programme", "", onDone = { name ->
            showNew = false
            ex.editProgram(ex.newProgram(name))
        }, onDismiss = { showNew = false })
    }
}

@Composable
fun ProgramEditScreen(ex: ExerciseViewModel) {
    val p = ex.programs.firstOrNull { it.id == ex.editingProgramId }
    if (p == null) {
        LaunchedEffect(Unit) { ex.back() }
        return
    }
    var showAddFiles by remember { mutableStateOf(false) }
    var showAddRoutine by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var pagesFor by remember { mutableStateOf<Int?>(null) }
    val lib = ex.library

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("←", fontSize = 26.sp, modifier = Modifier.clickable { ex.back() }.padding(8.dp))
            Text(
                p.name, Modifier.weight(1f).clickable { showRename = true },
                fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text("🗑", fontSize = 20.sp, modifier = Modifier.clickable { showDelete = true }.padding(8.dp))
            Pill("▶ Lancer", on = true) { ex.openProgram(p.id) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("+ Fichiers") { showAddFiles = true }
            Pill("+ Routine") { showAddRoutine = true }
            TimerPill(ex)
        }
        if (p.lines.isEmpty()) {
            Text("Ce programme est vide.", color = Pal.fg.copy(alpha = 0.6f), fontSize = 14.sp)
        } else {
            Text(
                "Touchez « Pages » pour cibler des pages d'un PDF (ex. 3-5 ou 1,4,7).",
                color = Pal.fg.copy(alpha = 0.5f), fontSize = 12.sp
            )
        }
        val drag = rememberDragReorderState()
        val rowH = 68.dp
        val rowGap = 6.dp
        val rowPx = with(LocalDensity.current) { (rowH + rowGap).toPx() }
        val keys = remember(p.lines) {
            val seen = HashMap<String, Int>()
            p.lines.map { val n = seen[it.ref] ?: 0; seen[it.ref] = n + 1; "${it.ref}#$n" }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(rowGap), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(p.lines, key = { i, _ -> keys[i] }) { idx, line ->
                val e = if (line.isRoutine) null else lib.firstOrNull { it.uri == line.ref }
                val routine = if (line.isRoutine) ex.routines.firstOrNull { it.id == line.ref } else null
                val k = keys[idx]
                Row(
                    Modifier
                        .then(reorderItem(drag, k))
                        .fillMaxWidth()
                        .height(rowH)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Pal.fg.copy(alpha = 0.06f))
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .reorderHandle(drag, k, rowPx, idx, p.lines.lastIndex) { f, t -> ex.moveInProgram(p.id, f, t - f) },
                        contentAlignment = Alignment.Center
                    ) { Text("≡", fontSize = 26.sp, color = Pal.fg.copy(alpha = 0.7f)) }
                    Text("${idx + 1}", Modifier.width(18.dp), color = Accent, fontWeight = FontWeight.Bold)
                    if (e != null) Thumb(e, Modifier.size(width = 40.dp, height = 52.dp).clip(RoundedCornerShape(6.dp)))
                    else Box(Modifier.size(width = 40.dp, height = 52.dp), contentAlignment = Alignment.Center) {
                        Text(if (line.isRoutine) "↪" else "?", fontSize = 22.sp, color = Accent)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                line.isRoutine -> "Routine : " + (routine?.name ?: "introuvable")
                                e != null -> e.name
                                else -> "Fichier introuvable"
                            },
                            fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (e == null && routine == null) Pal.fg.copy(alpha = 0.4f) else Pal.fg
                        )
                        if (routine != null) {
                            Text("${routine.items.size} exercice(s)", fontSize = 11.sp, color = Pal.fg.copy(alpha = 0.6f))
                        } else if (e != null && e.isPdf) {
                            Text(
                                "Pages : " + line.pages.ifBlank { "toutes" } + "  ✎",
                                fontSize = 12.sp, color = Accent,
                                modifier = Modifier.clickable { pagesFor = idx }
                            )
                        }
                    }
                    Text("✕", fontSize = 18.sp, modifier = Modifier.clickable { ex.removeProgramLine(p.id, idx) }.padding(8.dp))
                }
            }
        }
    }

    pagesFor?.let { i ->
        val line = p.lines.getOrNull(i)
        if (line != null) {
            TextPrompt(
                "Pages (ex. 3-5 ou 1,4,7 ; vide = toutes)", line.pages,
                onDone = { ex.setProgramPages(p.id, i, it); pagesFor = null },
                onDismiss = { pagesFor = null }
            )
        }
    }
    if (showRename) {
        TextPrompt("Renommer le programme", p.name, onDone = { ex.renameProgram(p.id, it); showRename = false },
            onDismiss = { showRename = false })
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Supprimer « ${p.name} » ?") },
            text = { Text("Les fichiers et les routines ne sont pas supprimés.") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; ex.deleteProgram(p.id); ex.back() }) {
                    Text("Supprimer", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Annuler") } }
        )
    }
    if (showAddRoutine) {
        AlertDialog(
            onDismissRequest = { showAddRoutine = false },
            title = { Text("Ajouter une routine") },
            text = {
                if (ex.routines.isEmpty()) {
                    Text("Aucune routine. Créez-en une dans l'onglet Routines.")
                } else {
                    LazyColumn {
                        items(ex.routines, key = { it.id }) { r ->
                            Text(
                                r.name,
                                Modifier.fillMaxWidth()
                                    .clickable { ex.addProgramRoutine(p.id, r.id); showAddRoutine = false }
                                    .padding(vertical = 12.dp),
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAddRoutine = false }) { Text("Annuler") } }
        )
    }
    if (showAddFiles) {
        val selected = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { showAddFiles = false },
            title = { Text("Ajouter des fichiers") },
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
                TextButton(onClick = { ex.addProgramFiles(p.id, selected.toList()); showAddFiles = false }) {
                    Text("Ajouter (${selected.size})", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { showAddFiles = false }) { Text("Annuler") } }
        )
    }
}
