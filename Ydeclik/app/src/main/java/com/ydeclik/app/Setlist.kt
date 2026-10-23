package com.ydeclik.app

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// ------------------------------------------------------------------ modèle

data class SPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val bpm: Int,
    val beats: Int,
    val unit: Int,
    val grouping: Int,
    val subs: List<Int>,
    val states: List<List<NoteState>>
) {
    val sig: TimeSig get() = TimeSig(beats, unit)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("bpm", bpm)
        put("beats", beats); put("unit", unit); put("grouping", grouping)
        put("subs", JSONArray(subs))
        put("states", JSONArray(states.map { row ->
            row.joinToString("") { when (it) { NoteState.ACCENT -> "A"; NoteState.REST -> "R"; else -> "N" } }
        }))
    }

    companion object {
        fun fromJson(o: JSONObject): SPreset {
            val subsA = o.getJSONArray("subs")
            val stA = o.getJSONArray("states")
            return SPreset(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "Preset"),
                bpm = o.optInt("bpm", 120),
                beats = o.optInt("beats", 4),
                unit = o.optInt("unit", 4),
                grouping = o.optInt("grouping", 0),
                subs = List(subsA.length()) { subsA.getInt(it) },
                states = List(stA.length()) { i ->
                    stA.getString(i).map { c -> when (c) { 'A' -> NoteState.ACCENT; 'R' -> NoteState.REST; else -> NoteState.NORMAL } }
                }
            )
        }

        /** Preset à partir d'un réglage de métronome. */
        fun from(name: String, c: Config) =
            SPreset(name = name, bpm = c.bpm, beats = c.sig.beats, unit = c.sig.unit,
                grouping = c.grouping, subs = c.subs, states = c.states)

        /** Preset vide : 4/4, 100 BPM, sans accent. */
        fun blank(name: String) = from(name, Config(bpm = 100).resetPattern())
    }

    /** Changement de métrique : la grille d'accents est remise à zéro. */
    fun withSig(s: TimeSig): SPreset {
        val c = Config(bpm = bpm, sig = s, grouping = 0).resetPattern()
        return copy(beats = s.beats, unit = s.unit, grouping = 0, subs = c.subs, states = c.states)
    }

    /** Réglage cohérent (au cas où les données enregistrées seraient corrompues). */
    fun safeConfig(): Config {
        val c = Config(bpm = bpm, sig = sig, grouping = grouping, subs = subs, states = states)
        return if (c.isConsistent()) c else Config(bpm = bpm, sig = sig).resetPattern()
    }
}

data class Setlist(val id: String = UUID.randomUUID().toString(), val name: String, val presets: List<SPreset> = emptyList())

class SetlistViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("ydeclik_sl", Context.MODE_PRIVATE)

    var setlists by mutableStateOf(emptyList<Setlist>()); private set
    var currentId by mutableStateOf<String?>(null); private set
    var index by mutableIntStateOf(0); private set
    var swipeNav by mutableStateOf(false); private set

    val current: Setlist? get() = setlists.firstOrNull { it.id == currentId }

    init {
        swipeNav = prefs.getBoolean("swipe", false)
        runCatching {
            val arr = JSONArray(prefs.getString("lists", "[]"))
            setlists = List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val pa = o.getJSONArray("presets")
                Setlist(o.getString("id"), o.optString("name", "Setlist"),
                    List(pa.length()) { SPreset.fromJson(pa.getJSONObject(it)) })
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        setlists.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name)
                put("presets", JSONArray().also { pa -> s.presets.forEach { pa.put(it.toJson()) } })
            })
        }
        prefs.edit().putString("lists", arr.toString()).apply()
    }

    private fun mutate(id: String, f: (Setlist) -> Setlist) {
        setlists = setlists.map { if (it.id == id) f(it) else it }
        save()
    }

    fun newSetlist(name: String): String {
        val s = Setlist(name = name.ifBlank { "Setlist ${setlists.size + 1}" })
        setlists = setlists + s
        save()
        return s.id
    }

    fun rename(id: String, name: String) { if (name.isNotBlank()) mutate(id) { it.copy(name = name.trim()) } }

    fun delete(id: String) {
        setlists = setlists.filter { it.id != id }
        if (currentId == id) currentId = null
        save()
    }

    fun open(id: String) { currentId = id; index = 0 }

    fun addPreset(p: SPreset) {
        val id = currentId ?: return
        mutate(id) { it.copy(presets = it.presets + p) }
    }

    fun editPreset(p: SPreset) {
        val id = currentId ?: return
        mutate(id) { s -> s.copy(presets = s.presets.map { if (it.id == p.id) p else it }) }
    }

    fun removePreset(pid: String) {
        val id = currentId ?: return
        mutate(id) { s -> s.copy(presets = s.presets.filter { it.id != pid }) }
        index = index.coerceIn(0, ((current?.presets?.size ?: 1) - 1).coerceAtLeast(0))
    }

    fun movePreset(from: Int, to: Int) {
        val id = currentId ?: return
        val list = current?.presets?.toMutableList() ?: return
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        mutate(id) { it.copy(presets = list) }
        index = when {
            index == from -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
    }

    fun select(i: Int) { index = i }

    fun go(delta: Int) {
        val n = current?.presets?.size ?: 0
        if (n == 0) return
        index = (index + delta).coerceIn(0, n - 1)
    }

    fun updateSwipe(v: Boolean) { swipeNav = v; prefs.edit().putBoolean("swipe", v).apply() }
}

fun applyPreset(vm: MetronomeViewModel, p: SPreset) {
    val c = p.safeConfig()
    vm.applyPattern(c.bpm, c.sig, c.grouping, c.subs, c.states)
}

// ------------------------------------------------------------------ liste des setlists

@Composable
fun SetlistsScreen(ex: ExerciseViewModel, sl: SetlistViewModel) {
    var showNew by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeaderBar(ex, "Setlists")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("+ Nouvelle setlist") { showNew = true }
        }
        if (sl.setlists.isEmpty()) {
            Text(
                "Aucune setlist. Créez-en une, puis ajoutez-y des presets (BPM, métrique, accents).",
                color = Pal.fg.copy(alpha = 0.6f), fontSize = 14.sp
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(sl.setlists, key = { it.id }) { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Pal.fg.copy(alpha = 0.06f))
                        .clickable { sl.open(s.id); ex.push(Screen.SETLIST_EDIT) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${s.presets.size} preset(s)", fontSize = 12.sp, color = Pal.fg.copy(alpha = 0.6f))
                    }
                    Pill("● Live", on = true) {
                        sl.open(s.id)
                        ex.push(Screen.SETLIST_LIVE)
                    }
                }
            }
        }
    }
    if (showNew) {
        TextPrompt("Nom de la setlist", "", onDone = {
            val id = sl.newSetlist(it)
            sl.open(id)
            showNew = false
            ex.push(Screen.SETLIST_EDIT)
        }, onDismiss = { showNew = false })
    }
}

// ------------------------------------------------------------------ édition

private val ROW_H = 92.dp
private val ROW_GAP = 6.dp

@Composable
fun SetlistEditScreen(vm: MetronomeViewModel, ex: ExerciseViewModel, sl: SetlistViewModel) {
    val s = sl.current
    if (s == null) {
        LaunchedEffect(Unit) { ex.back() }
        return
    }
    var renameSetlist by remember { mutableStateOf(false) }
    var deleteSetlist by remember { mutableStateOf(false) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var bpmId by remember { mutableStateOf<String?>(null) }
    var sigMenuId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("←", fontSize = 26.sp, modifier = Modifier.clickable { ex.back() }.padding(8.dp))
            Text(
                s.name, Modifier.weight(1f).clickable { renameSetlist = true },
                fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text("🗑", fontSize = 20.sp, modifier = Modifier.clickable { deleteSetlist = true }.padding(8.dp))
            Pill("● Live", on = true) { ex.push(Screen.SETLIST_LIVE) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("+ Réglage actuel") {
                sl.addPreset(SPreset.from("Preset ${s.presets.size + 1}", vm.cfg))
            }
            Pill("+ Preset vide") { sl.addPreset(SPreset.blank("Preset ${s.presets.size + 1}")) }
        }

        val drag = rememberDragReorderState()
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(ROW_GAP),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(s.presets, key = { it.id }) { p ->
                val idx = s.presets.indexOfFirst { it.id == p.id }
                val idxState by rememberUpdatedState(idx)
                val lastIdx by rememberUpdatedState(s.presets.lastIndex)
                val selected = idx == sl.index
                val density = androidx.compose.ui.platform.LocalDensity.current
                val rowPx = with(density) { (ROW_H + ROW_GAP).toPx() }

                Column(
                    Modifier
                        .then(reorderItem(drag, p.id))
                        .fillMaxWidth()
                        .height(ROW_H)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Pal.fg.copy(alpha = if (selected) 0.14f else 0.06f))
                        .then(if (selected) Modifier.border(1.5.dp, Accent, RoundedCornerShape(12.dp)) else Modifier)
                        .clickable { sl.select(idx); applyPreset(vm, p) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Poignée : glisser pour réordonner
                        Box(
                            Modifier
                                .size(40.dp)
                                .reorderHandle(drag, p.id, rowPx, idx, s.presets.lastIndex) { f, t -> sl.movePreset(f, t) },
                            contentAlignment = Alignment.Center
                        ) { Text("≡", fontSize = 26.sp, color = Pal.fg.copy(alpha = 0.7f)) }
                        Text(
                            p.name, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text("✎", fontSize = 20.sp, modifier = Modifier.clickable { renameId = p.id }.padding(8.dp))
                        Text("✕", fontSize = 18.sp, modifier = Modifier.clickable { sl.removePreset(p.id) }.padding(8.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 40.dp)
                    ) {
                        Text(
                            "${p.bpm} BPM", color = Accent, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Pal.fg.copy(alpha = 0.1f))
                                .clickable { bpmId = p.id }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                        Box {
                            Text(
                                p.sig.label + " ▾", fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Pal.fg.copy(alpha = 0.1f))
                                    .clickable { sigMenuId = p.id }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                            DropdownMenu(expanded = sigMenuId == p.id, onDismissRequest = { sigMenuId = null }) {
                                TimeSig.ALL.forEach { t ->
                                    DropdownMenuItem(
                                        text = { Text(t.label, color = if (t == p.sig) Accent else Color.Unspecified) },
                                        onClick = {
                                            sigMenuId = null
                                            val np = p.withSig(t)
                                            sl.editPreset(np)
                                            if (selected) applyPreset(vm, np)
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "⟳ Réglage actuel", fontSize = 12.sp, color = Pal.fg.copy(alpha = 0.8f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { sl.editPreset(SPreset.from(p.name, vm.cfg).copy(id = p.id)) }
                                .padding(8.dp)
                        )
                    }
                }
            }
        }

        // Tap tempo + lecture
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .weight(1f).height(56.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Pal.fg.copy(alpha = 0.14f))
                    .clickable {
                        val before = vm.cfg.bpm
                        vm.tap()
                        val after = vm.cfg.bpm
                        val sel = s.presets.getOrNull(sl.index)
                        if (after != before && sel != null) sl.editPreset(sel.copy(bpm = after))
                    },
                contentAlignment = Alignment.Center
            ) { Text("TAP", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            Text("${vm.cfg.bpm}", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Accent)
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(Accent).clickable { vm.toggle() },
                contentAlignment = Alignment.Center
            ) { Text(if (vm.isPlaying) "■" else "▶", fontSize = 24.sp, color = Color.White) }
        }
    }

    if (renameSetlist) {
        TextPrompt("Nom de la setlist", s.name, onDone = { sl.rename(s.id, it); renameSetlist = false },
            onDismiss = { renameSetlist = false })
    }
    if (deleteSetlist) {
        AlertDialog(
            onDismissRequest = { deleteSetlist = false },
            title = { Text("Supprimer « ${s.name} » ?") },
            confirmButton = {
                TextButton(onClick = { deleteSetlist = false; sl.delete(s.id); ex.back() }) {
                    Text("Supprimer", color = Accent)
                }
            },
            dismissButton = { TextButton(onClick = { deleteSetlist = false }) { Text("Annuler") } }
        )
    }
    renameId?.let { id ->
        val p = s.presets.firstOrNull { it.id == id }
        if (p != null) {
            TextPrompt("Nom du preset", p.name, onDone = {
                if (it.isNotBlank()) sl.editPreset(p.copy(name = it.trim()))
                renameId = null
            }, onDismiss = { renameId = null })
        } else renameId = null
    }
    bpmId?.let { id ->
        val p = s.presets.firstOrNull { it.id == id }
        if (p != null) {
            TextPrompt("BPM", p.bpm.toString(), numeric = true, onDone = {
                it.toIntOrNull()?.let { v ->
                    val np = p.copy(bpm = v.coerceIn(20, 300))
                    sl.editPreset(np)
                    if (s.presets.getOrNull(sl.index)?.id == np.id) vm.setBPM(np.bpm)
                }
                bpmId = null
            }, onDismiss = { bpmId = null })
        } else bpmId = null
    }
}

// ------------------------------------------------------------------ live

@Composable
fun SetlistLiveScreen(vm: MetronomeViewModel, ex: ExerciseViewModel, sl: SetlistViewModel) {
    val s = sl.current
    if (s == null || s.presets.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text("←", fontSize = 26.sp, modifier = Modifier.clickable { ex.back() }.padding(8.dp))
            Text("Cette setlist est vide.", Modifier.padding(16.dp), color = Pal.fg.copy(alpha = 0.6f))
        }
        return
    }
    val idx = sl.index.coerceIn(0, s.presets.lastIndex)
    val cur = s.presets[idx]
    val next = s.presets.getOrNull(idx + 1)

    // Chaque changement de preset rappelle immédiatement ses réglages.
    LaunchedEffect(cur.id, cur.bpm, cur.beats, cur.unit) { applyPreset(vm, cur) }

    val swipeOn = sl.swipeNav
    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp)
            .pointerInput(swipeOn) {
                if (swipeOn) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total < -120f) sl.go(1) else if (total > 120f) sl.go(-1)
                        },
                        onHorizontalDrag = { c, d -> c.consume(); total += d }
                    )
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("←", fontSize = 26.sp, modifier = Modifier.clickable { ex.back() }.padding(8.dp))
            Text(
                s.name, Modifier.weight(1f), fontSize = 13.sp, color = Pal.fg.copy(alpha = 0.6f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text("${idx + 1} / ${s.presets.size}", color = Accent, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            cur.name, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth()
        )
        Text(
            "${vm.cfg.bpm} BPM · ${cur.sig.label}", fontSize = 18.sp,
            color = Pal.fg.copy(alpha = 0.7f)
        )

        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val canPrev = idx > 0
            val canNext = idx < s.presets.lastIndex
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .background(Pal.fg.copy(alpha = if (canPrev) 0.16f else 0.06f))
                    .clickable(enabled = canPrev) { sl.go(-1) },
                contentAlignment = Alignment.Center
            ) { Text("◀", fontSize = 24.sp, color = if (canPrev) Pal.fg else Pal.fg.copy(alpha = 0.3f)) }

            Box(
                Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(Accent)
                    .border(
                        8.dp,
                        if (vm.flash) (if (vm.flashAccent) Pal.fg else Color(0xFF34C759)) else Color.Transparent,
                        CircleShape
                    )
                    .clickable { vm.toggle() },
                contentAlignment = Alignment.Center
            ) { Text(if (vm.isPlaying) "■" else "▶", fontSize = 72.sp, color = Color.White) }

            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .background(Pal.fg.copy(alpha = if (canNext) 0.16f else 0.06f))
                    .clickable(enabled = canNext) { sl.go(1) },
                contentAlignment = Alignment.Center
            ) { Text("▶", fontSize = 24.sp, color = if (canNext) Pal.fg else Pal.fg.copy(alpha = 0.3f)) }
        }
        Spacer(Modifier.weight(1f))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = swipeOn, onCheckedChange = { sl.updateSwipe(it) })
            Text("Navigation par balayage", fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Pal.fg.copy(alpha = 0.14f))
                    .clickable { vm.tap() }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) { Text("TAP", fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Pal.fg.copy(alpha = 0.06f))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Tag("SUIVANT")
            if (next != null) {
                Text(next.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${next.bpm} BPM · ${next.sig.label}", fontSize = 13.sp, color = Pal.fg.copy(alpha = 0.6f))
            } else {
                Text("Fin de la setlist", fontSize = 16.sp, color = Pal.fg.copy(alpha = 0.6f))
            }
        }
    }
}
