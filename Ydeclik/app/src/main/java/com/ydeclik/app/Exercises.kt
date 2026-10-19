package com.ydeclik.app

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

data class Exercise(val uri: String, val name: String, val isPdf: Boolean, val fromAlbum: Boolean = false)

/** Per-exercise practice statistics, keyed by the file URI. */
data class ExStats(
    val lastOpened: Long = 0L,
    val firstBpm: Int? = null,
    val lastBpm: Int? = null,
    val targetBpm: Int? = null
)

data class Routine(val id: String, val name: String, val items: List<String>)

data class Album(val id: Long, val name: String, val count: Int)

enum class Screen { METRONOME, EXERCISES, ROUTINES, ROUTINE_EDIT, VIEWER }

fun fmtDate(ms: Long): String =
    if (ms <= 0L) "Never"
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

class ExerciseViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ydeclik_ex", Context.MODE_PRIVATE)
    private val cr: ContentResolver get() = app.contentResolver

    // ------------------------------------------------------------ navigation

    val stack = mutableStateListOf(Screen.METRONOME)
    val screen: Screen get() = stack.last()

    /** Switch between the three main tabs. */
    fun tab(s: Screen) {
        stack.clear()
        stack.add(Screen.METRONOME)
        if (s != Screen.METRONOME) stack.add(s)
    }

    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    // ------------------------------------------------------------ state

    var imported by mutableStateOf(emptyList<Exercise>()); private set
    var albumItems by mutableStateOf(emptyList<Exercise>()); private set
    var album by mutableStateOf<Album?>(null); private set
    var albums by mutableStateOf(emptyList<Album>()); private set
    var stats by mutableStateOf(emptyMap<String, ExStats>()); private set
    var routines by mutableStateOf(emptyList<Routine>()); private set
    var autoApplyBpm by mutableStateOf(true); private set

    var viewerItems by mutableStateOf(emptyList<Exercise>()); private set
    var viewerStart by mutableIntStateOf(0); private set
    var viewerTitle by mutableStateOf<String?>(null); private set
    var editingRoutineId by mutableStateOf<String?>(null); private set

    /** Imported files first, then the files of the chosen gallery album. */
    val library: List<Exercise>
        get() {
            val known = imported.map { it.uri }.toSet()
            return imported + albumItems.filter { it.uri !in known }
        }

    init {
        runCatching {
            prefs.getString("imported", null)?.let { s ->
                val a = JSONArray(s)
                imported = List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    Exercise(o.getString("uri"), o.getString("name"), o.optBoolean("pdf"))
                }
            }
            prefs.getString("stats", null)?.let { s ->
                val o = JSONObject(s)
                val m = HashMap<String, ExStats>()
                o.keys().forEach { k ->
                    val e = o.getJSONObject(k)
                    m[k] = ExStats(
                        e.optLong("last", 0L),
                        e.optInt("first", -1).takeIf { it > 0 },
                        e.optInt("lastBpm", -1).takeIf { it > 0 },
                        e.optInt("target", -1).takeIf { it > 0 }
                    )
                }
                stats = m
            }
            prefs.getString("routines", null)?.let { s ->
                val a = JSONArray(s)
                routines = List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    val it = o.getJSONArray("items")
                    Routine(o.getString("id"), o.getString("name"), List(it.length()) { j -> it.getString(j) })
                }
            }
            val aid = prefs.getLong("albumId", -1L)
            if (aid >= 0) album = Album(aid, prefs.getString("albumName", "") ?: "", 0)
            autoApplyBpm = prefs.getBoolean("autoApply", true)
        }
        refreshAlbumItems()
    }

    // ------------------------------------------------------------ persistence

    private fun saveImported() {
        val a = JSONArray()
        imported.forEach {
            a.put(JSONObject().put("uri", it.uri).put("name", it.name).put("pdf", it.isPdf))
        }
        prefs.edit().putString("imported", a.toString()).apply()
    }

    private fun saveStats() {
        val o = JSONObject()
        stats.forEach { (k, v) ->
            o.put(
                k, JSONObject()
                    .put("last", v.lastOpened)
                    .put("first", v.firstBpm ?: -1)
                    .put("lastBpm", v.lastBpm ?: -1)
                    .put("target", v.targetBpm ?: -1)
            )
        }
        prefs.edit().putString("stats", o.toString()).apply()
    }

    private fun saveRoutines() {
        val a = JSONArray()
        routines.forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("items", JSONArray(it.items)))
        }
        prefs.edit().putString("routines", a.toString()).apply()
    }

    // ------------------------------------------------------------ library

    private fun displayName(uri: Uri): String = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: (uri.lastPathSegment ?: "file")

    fun addImported(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val added = ArrayList<Exercise>()
            for (u in uris) {
                runCatching { cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val name = displayName(u)
                val mime = cr.getType(u) ?: ""
                added.add(Exercise(u.toString(), name, mime == "application/pdf" || name.endsWith(".pdf", true)))
            }
            withContext(Dispatchers.Main) {
                val known = imported.map { it.uri }.toSet()
                imported = imported + added.filter { it.uri !in known }
                saveImported()
            }
        }
    }

    fun removeImported(uri: String) {
        imported = imported.filter { it.uri != uri }
        saveImported()
    }

    // ------------------------------------------------------------ gallery album

    private fun queryAlbums(): List<Album> {
        val map = LinkedHashMap<Long, Pair<String, Int>>()
        val proj = arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        cr.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nI = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idI)
                val cur = map[id]
                map[id] = (cur?.first ?: (c.getString(nI) ?: "?")) to ((cur?.second ?: 0) + 1)
            }
        }
        return map.map { Album(it.key, it.value.first, it.value.second) }.sortedBy { it.name.lowercase() }
    }

    private fun queryAlbumItems(bucketId: Long): List<Exercise> {
        val out = ArrayList<Exercise>()
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        cr.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
            "${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(bucketId.toString()),
            "${MediaStore.Images.Media.DISPLAY_NAME} ASC"
        )?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nI = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(idI))
                out.add(Exercise(uri.toString(), c.getString(nI) ?: "image", false, true))
            }
        }
        return out
    }

    fun refreshAlbums() {
        viewModelScope.launch(Dispatchers.IO) {
            val l = runCatching { queryAlbums() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { albums = l }
        }
    }

    fun refreshAlbumItems() {
        val a = album ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val l = runCatching { queryAlbumItems(a.id) }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { albumItems = l }
        }
    }

    fun chooseAlbum(a: Album?) {
        album = a
        prefs.edit().apply {
            if (a == null) { remove("albumId"); remove("albumName") }
            else { putLong("albumId", a.id); putString("albumName", a.name) }
        }.apply()
        if (a == null) albumItems = emptyList() else refreshAlbumItems()
    }

    fun updateAutoApply(v: Boolean) {
        autoApplyBpm = v
        prefs.edit().putBoolean("autoApply", v).apply()
    }

    // ------------------------------------------------------------ stats

    private fun putStats(uri: String, f: (ExStats) -> ExStats) {
        val cur = stats[uri] ?: ExStats()
        val next = f(cur)
        if (next == cur) return
        stats = stats + (uri to next)
        saveStats()
    }

    fun markOpened(uri: String) = putStats(uri) { it.copy(lastOpened = System.currentTimeMillis()) }

    /** Called while the metronome is playing on this exercise. */
    fun recordBpm(uri: String, bpm: Int) = putStats(uri) { it.copy(firstBpm = it.firstBpm ?: bpm, lastBpm = bpm) }

    fun updateTarget(uri: String, bpm: Int?) = putStats(uri) { it.copy(targetBpm = bpm) }

    fun resetStats(uri: String) {
        stats = stats - uri
        saveStats()
    }

    // ------------------------------------------------------------ routines

    private fun updateRoutine(id: String, f: (Routine) -> Routine) {
        routines = routines.map { if (it.id == id) f(it) else it }
        saveRoutines()
    }

    fun newRoutine(name: String): String {
        val r = Routine(UUID.randomUUID().toString(), name.ifBlank { "Routine ${routines.size + 1}" }, emptyList())
        routines = routines + r
        saveRoutines()
        return r.id
    }

    fun renameRoutine(id: String, name: String) {
        if (name.isNotBlank()) updateRoutine(id) { it.copy(name = name.trim()) }
    }

    fun deleteRoutine(id: String) {
        routines = routines.filter { it.id != id }
        saveRoutines()
    }

    fun addToRoutine(id: String, uris: List<String>) = updateRoutine(id) { it.copy(items = it.items + uris) }

    fun removeFromRoutine(id: String, index: Int) = updateRoutine(id) {
        it.copy(items = it.items.toMutableList().also { l -> if (index in l.indices) l.removeAt(index) })
    }

    fun moveInRoutine(id: String, index: Int, delta: Int) = updateRoutine(id) { r ->
        val to = index + delta
        if (index !in r.items.indices || to !in r.items.indices) r
        else r.copy(items = r.items.toMutableList().also { l -> l.add(to, l.removeAt(index)) })
    }

    // ------------------------------------------------------------ opening

    fun openExercise(e: Exercise) {
        viewerItems = listOf(e)
        viewerStart = 0
        viewerTitle = null
        stack.add(Screen.VIEWER)
    }

    fun openRoutine(id: String, start: Int = 0) {
        val r = routines.firstOrNull { it.id == id } ?: return
        val lib = library
        val items = r.items.mapNotNull { u -> lib.firstOrNull { it.uri == u } }
        if (items.isEmpty()) return
        viewerItems = items
        viewerStart = start.coerceIn(0, items.lastIndex)
        viewerTitle = r.name
        stack.add(Screen.VIEWER)
    }

    fun editRoutine(id: String) {
        editingRoutineId = id
        stack.add(Screen.ROUTINE_EDIT)
    }
}
