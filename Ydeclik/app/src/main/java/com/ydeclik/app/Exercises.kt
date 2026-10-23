package com.ydeclik.app

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Exercise(val uri: String, val name: String, val isPdf: Boolean, val fromAlbum: Boolean = false)

/** Statistiques de BPM. Une entrée par fichier image, et une par page pour un PDF (voir [statKey]). */
data class ExStats(val firstBpm: Int? = null, val lastBpm: Int? = null, val targetBpm: Int? = null)

/** Informations propres au fichier : dernière ouverture et dernière page ouverte. */
data class FileMeta(val lastOpened: Long = 0L, val lastPage: Int = 0)

/** Une entrée de routine : un fichier et un minuteur facultatif en minutes (0 = pas de minuteur). */
data class RoutineItem(val uri: String, val minutes: Int = 0)

data class Routine(
    val id: String,
    val name: String,
    val items: List<RoutineItem>,
    val autoAdvance: Boolean = false
)

data class Album(val id: Long, val name: String, val count: Int)

data class DayUsage(val day: String, val appSec: Long, val playSec: Long)

enum class SortMode { NAME, RECENT }

enum class Screen {
    METRONOME, EXERCISES, ROUTINES, ROUTINE_EDIT, VIEWER, USAGE, SETLISTS, SETLIST_EDIT, SETLIST_LIVE
}

/** Clé des statistiques : le fichier (page 1 ou image) ou « fichier#pN » pour les pages suivantes d'un PDF. */
fun statKey(uri: String, page: Int): String = if (page <= 0) uri else "$uri#p$page"

fun fmtDate(ms: Long): String =
    if (ms <= 0L) "Jamais"
    else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

fun dayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

class ExerciseViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("ydeclik_ex", Context.MODE_PRIVATE)
    private val cr: ContentResolver get() = app.contentResolver

    // ------------------------------------------------------------ navigation

    val stack = mutableStateListOf(Screen.METRONOME)
    val screen: Screen get() = stack.last()

    /** Vrai quand le fichier est affiché en plein écran (double toucher). */
    var immersive by mutableStateOf(false)

    /** Passe d'un onglet principal à un autre. */
    fun tab(s: Screen) {
        immersive = false
        stack.clear()
        stack.add(Screen.METRONOME)
        if (s != Screen.METRONOME) stack.add(s)
    }

    fun push(s: Screen) { stack.add(s) }

    fun back() {
        immersive = false
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    // ------------------------------------------------------------ état

    var imported by mutableStateOf(emptyList<Exercise>()); private set
    var albumItems by mutableStateOf(emptyList<Exercise>()); private set
    var album by mutableStateOf<Album?>(null); private set
    var albums by mutableStateOf(emptyList<Album>()); private set
    var stats by mutableStateOf(emptyMap<String, ExStats>()); private set
    var meta by mutableStateOf(emptyMap<String, FileMeta>()); private set
    var routines by mutableStateOf(emptyList<Routine>()); private set
    var autoApplyBpm by mutableStateOf(true); private set
    var sortMode by mutableStateOf(SortMode.NAME); private set

    /** Dossier surveillé (ex. dossier Dropbox où GoodNotes sauvegarde ses PDF). */
    var folderUri by mutableStateOf<String?>(null); private set
    var folderName by mutableStateOf(""); private set
    var folderItems by mutableStateOf(emptyList<Exercise>()); private set

    /** Minuteur global (minutes, 0 = aucun), commun aux exercices et aux routines. */
    var timerMin by mutableIntStateOf(0); private set

    // Le minuteur est indépendant du métronome et des écrans : comme un minuteur tiers du téléphone.
    var timerRemaining by mutableIntStateOf(0); private set
    var timerRunning by mutableStateOf(false); private set
    var timerFinished by mutableStateOf(false); private set
    private var timerJob: Job? = null

    /** Sonnerie de fin de minuteur (null = alarme par défaut du téléphone). */
    var alarmUri by mutableStateOf<String?>(null); private set

    /** Appelé à la fin du minuteur : arrête le métronome. */
    var onTimerFinished: () -> Unit = {}

    /** Affiche la boîte de réglages (accessible depuis le menu). */
    var settingsOpen by mutableStateOf(false)

    var viewerItems by mutableStateOf(emptyList<Exercise>()); private set
    var viewerMinutes by mutableStateOf(emptyList<Int>()); private set
    var viewerAutoAdvance by mutableStateOf(false); private set
    var viewerStart by mutableIntStateOf(0); private set
    var viewerTitle by mutableStateOf<String?>(null); private set
    var editingRoutineId by mutableStateOf<String?>(null); private set

    // Temps d'utilisation par jour (millisecondes : [temps dans l'appli, temps avec le métronome en marche])
    var playingProvider: () -> Boolean = { false }
    var todayAppSec by mutableLongStateOf(0L); private set
    var todayPlaySec by mutableLongStateOf(0L); private set
    private val usageDays = LinkedHashMap<String, LongArray>()
    private var foreground = false

    /** Fichiers importés d'abord, puis ceux de l'album choisi dans la galerie. */
    val library: List<Exercise>
        get() {
            val known = imported.map { it.uri }.toSet()
            val known2 = known + imported.map { it.uri }
            return imported + (folderItems + albumItems).filter { it.uri !in known2 }.distinctBy { it.uri }
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
        }
        runCatching {
            prefs.getString("stats", null)?.let { s ->
                val o = JSONObject(s)
                val m = HashMap<String, ExStats>()
                o.keys().forEach { k ->
                    val e = o.getJSONObject(k)
                    m[k] = ExStats(
                        e.optInt("first", -1).takeIf { it > 0 },
                        e.optInt("lastBpm", -1).takeIf { it > 0 },
                        e.optInt("target", -1).takeIf { it > 0 }
                    )
                }
                stats = m
            }
        }
        runCatching {
            prefs.getString("meta", null)?.let { s ->
                val o = JSONObject(s)
                val m = HashMap<String, FileMeta>()
                o.keys().forEach { k ->
                    val e = o.getJSONObject(k)
                    m[k] = FileMeta(e.optLong("last", 0L), e.optInt("page", 0))
                }
                meta = m
            }
        }
        runCatching {
            prefs.getString("routines", null)?.let { s ->
                val a = JSONArray(s)
                routines = List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    val arr = o.getJSONArray("items")
                    val items = List(arr.length()) { j ->
                        val v = arr.get(j)
                        if (v is JSONObject) RoutineItem(v.getString("uri"), v.optInt("min", 0))
                        else RoutineItem(v.toString())
                    }
                    Routine(o.getString("id"), o.getString("name"), items, o.optBoolean("auto", false))
                }
            }
        }
        runCatching {
            // Les identifiants d'album (BUCKET_ID) peuvent être négatifs : on teste la présence de la clé.
            if (prefs.contains("albumId")) {
                album = Album(prefs.getLong("albumId", 0L), prefs.getString("albumName", "") ?: "", 0)
            }
            autoApplyBpm = prefs.getBoolean("autoApply", true)
            timerMin = prefs.getInt("timerMin", 0)
            Pal.dark = prefs.getBoolean("dark", true)
            timerRemaining = timerMin * 60
            alarmUri = prefs.getString("alarmUri", null)
            folderUri = prefs.getString("folderUri", null)
            folderName = prefs.getString("folderName", "") ?: ""
            sortMode = SortMode.valueOf(prefs.getString("sort", "NAME") ?: "NAME")
        }
        refreshAlbumItems()
        refreshFolder()
        checkMissing()
        loadUsage()
        startUsageLoop()
    }

    // ------------------------------------------------------------ persistance

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
                    .put("first", v.firstBpm ?: -1)
                    .put("lastBpm", v.lastBpm ?: -1)
                    .put("target", v.targetBpm ?: -1)
            )
        }
        prefs.edit().putString("stats", o.toString()).apply()
    }

    private fun saveMeta() {
        val o = JSONObject()
        meta.forEach { (k, v) -> o.put(k, JSONObject().put("last", v.lastOpened).put("page", v.lastPage)) }
        prefs.edit().putString("meta", o.toString()).apply()
    }

    private fun saveRoutines() {
        val a = JSONArray()
        routines.forEach { r ->
            val items = JSONArray()
            r.items.forEach { ri -> items.put(JSONObject().put("uri", ri.uri).put("min", ri.minutes)) }
            a.put(JSONObject().put("id", r.id).put("name", r.name).put("items", items).put("auto", r.autoAdvance))
        }
        prefs.edit().putString("routines", a.toString()).apply()
    }

    // ------------------------------------------------------------ bibliothèque

    private fun displayName(uri: Uri): String = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: (uri.lastPathSegment ?: "fichier")

    fun addImported(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val added = ArrayList<Exercise>()
            var toFolder = 0
            for (u in uris) {
                runCatching { cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val name = displayName(u)
                val mime = cr.getType(u) ?: ""
                val isPdf = mime == "application/pdf" || name.endsWith(".pdf", true)
                if (copyToFolder(u, name, mime)) { toFolder++; continue }
                added.add(Exercise(copyIfDropbox(u, name) ?: u.toString(), name, isPdf))
            }
            withContext(Dispatchers.Main) {
                val known = imported.map { it.uri }.toSet()
                imported = imported + added.filter { it.uri !in known }
                saveImported()
                if (toFolder > 0) refreshFolder()
            }
        }
    }

    /** Fichier Dropbox + dossier choisi : copie dans ce dossier (visible ensuite par le scan). */
    private fun copyToFolder(u: Uri, name: String, mime: String): Boolean {
        if (u.authority?.contains("dropbox", ignoreCase = true) != true) return false
        val tree = folderUri?.let { Uri.parse(it) } ?: return false
        return runCatching {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val type = mime.ifBlank { if (name.endsWith(".pdf", true)) "application/pdf" else "application/octet-stream" }
            val base = name.substringBeforeLast('.', name)
            val dest = DocumentsContract.createDocument(cr, parent, type, base) ?: return false
            val ok = cr.openInputStream(u)?.use { input ->
                cr.openOutputStream(dest)?.use { out -> input.copyTo(out); true } ?: false
            } ?: false
            if (!ok) runCatching { DocumentsContract.deleteDocument(cr, dest) }
            ok
        }.getOrDefault(false)
    }

    /** Fichiers Dropbox uniquement : copie locale (hors connexion). Renvoie l'URI de la copie, ou null. */
    private fun copyIfDropbox(u: Uri, name: String): String? {
        if (u.authority?.contains("dropbox", ignoreCase = true) != true) return null
        return runCatching {
            val dir = java.io.File(app.filesDir, "dropbox").apply { mkdirs() }
            val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "fichier" }
            val f = java.io.File(dir, safe)
            cr.openInputStream(u)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: return null
            Uri.fromFile(f).toString()
        }.getOrNull()
    }

    fun removeImported(uri: String) {
        runCatching {
            val f = Uri.parse(uri).path?.let { java.io.File(it) }
            if (uri.startsWith("file:") && f != null && f.parentFile?.name == "dropbox") f.delete()
        }
        imported = imported.filter { it.uri != uri }
        saveImported()
    }

    fun updateSort(m: SortMode) {
        sortMode = m
        prefs.edit().putString("sort", m.name).apply()
    }

    /** Mode sombre (vrai) ou clair (faux). */
    fun updateDark(v: Boolean) {
        Pal.dark = v
        prefs.edit().putBoolean("dark", v).apply()
    }

    fun updateTimer(min: Int) {
        timerMin = min.coerceIn(0, 600)
        prefs.edit().putInt("timerMin", timerMin).apply()
        timerReset()
    }

    fun timerReset() {
        timerJob?.cancel()
        timerRunning = false
        timerFinished = false
        timerRemaining = timerMin * 60
    }

    private fun timerStart() {
        if (timerMin <= 0) return
        if (timerFinished || timerRemaining <= 0) { timerFinished = false; timerRemaining = timerMin * 60 }
        timerJob?.cancel()
        timerRunning = true
        val endAt = SystemClock.elapsedRealtime() + timerRemaining * 1000L
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(200)
                val left = endAt - SystemClock.elapsedRealtime()
                timerRemaining = ((left + 999) / 1000).toInt().coerceAtLeast(0)
                if (left <= 0) {
                    timerRunning = false
                    timerFinished = true
                    onTimerFinished()
                    alertTimeUp(app, alarmUri)
                    break
                }
            }
        }
    }

    fun updateAlarm(uri: String?) {
        alarmUri = uri
        prefs.edit().apply { if (uri == null) remove("alarmUri") else putString("alarmUri", uri) }.apply()
    }

    fun timerToggle() {
        if (timerRunning) { timerJob?.cancel(); timerRunning = false } else timerStart()
    }

    /** Relance depuis le début ; reste en marche si le minuteur tournait (mode routine : nouvel exercice). */
    fun timerRestart() {
        val was = timerRunning
        timerReset()
        if (was) timerStart()
    }

    fun updateAutoApply(v: Boolean) {
        autoApplyBpm = v
        prefs.edit().putBoolean("autoApply", v).apply()
    }

    // ------------------------------------------------------------ album de la galerie

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

    /** Choisit (ou retire, si [uri] est nul) le dossier surveillé. */
    fun chooseFolder(uri: Uri?) {
        if (uri == null) {
            folderUri = null; folderName = ""; folderItems = emptyList()
            prefs.edit().remove("folderUri").remove("folderName").apply()
            return
        }
        runCatching {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure {
            runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        folderUri = uri.toString()
        folderName = Uri.decode(uri.lastPathSegment ?: "").substringAfterLast('/').substringAfterLast(':')
        prefs.edit().putString("folderUri", folderUri).putString("folderName", folderName).apply()
        refreshFolder()
    }

    /** Relit le dossier : appelé au démarrage, au retour dans l'appli et par le bouton d'actualisation. */
    /** Parcourt un dossier (3 niveaux) et renvoie les PDF / JPG / PNG trouvés. Appel bloquant. */
    private fun scanTree(tree: Uri): List<Exercise> {
        val out = ArrayList<Exercise>()
        fun scan(docId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            runCatching {
                cr.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < 3) scan(id, depth + 1)
                        } else {
                            val pdf = mime == "application/pdf" || name.endsWith(".pdf", true)
                            val img = mime == "image/jpeg" || mime == "image/png" ||
                                name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) || name.endsWith(".png", true)
                            if (pdf || img) {
                                out.add(Exercise(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, pdf))
                            }
                        }
                    }
                }
            }
        }
        runCatching { scan(DocumentsContract.getTreeDocumentId(tree), 0) }
        return out
    }

    fun refreshFolder() {
        val tree = folderUri?.let { Uri.parse(it) } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val out = scanTree(tree)
            withContext(Dispatchers.Main) { folderItems = out.sortedBy { it.name.lowercase() } }
        }
    }

    // ------------------------------------------------------------ sauvegarde : fichiers introuvables et reliage

    /** Nombre de fichiers (importés ou utilisés dans une routine) qui ne peuvent plus être ouverts. */
    var missingCount by mutableIntStateOf(0); private set

    /** Message de résultat du dernier reliage. */
    var relinkMessage by mutableStateOf<String?>(null)

    private fun isReadable(uri: String): Boolean =
        runCatching { cr.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false }.getOrDefault(false)

    private fun baseUri(key: String): String = key.replace(Regex("#p\\d+$"), "")

    private fun oldNames(): Map<String, String> = runCatching {
        val o = JSONObject(prefs.getString("oldNames", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    /** uri -> nom, écrit dans la sauvegarde. */
    fun fileNameMap(): Map<String, String> {
        val m = HashMap<String, String>(oldNames())
        library.forEach { m[it.uri] = it.name }
        return m
    }

    fun checkMissing() {
        viewModelScope.launch(Dispatchers.IO) {
            val uris = (imported.map { it.uri } + routines.flatMap { r -> r.items.map { it.uri } }).toSet()
            val n = uris.count { !isReadable(it) }
            withContext(Dispatchers.Main) { missingCount = n }
        }
    }

    /** Relie les fichiers introuvables à des fichiers de même nom parmi [extra] et la bibliothèque actuelle. */
    private fun relink(extra: List<Exercise>) {
        viewModelScope.launch(Dispatchers.IO) {
            val cands = (extra + library).groupBy { it.name.lowercase() }
            val names = HashMap<String, String>(oldNames())
            imported.forEach { names[it.uri] = it.name }
            val olds = HashSet<String>()
            olds += imported.map { it.uri }
            olds += stats.keys.map { baseUri(it) }
            olds += meta.keys
            routines.forEach { r -> olds += r.items.map { it.uri } }
            val map = HashMap<String, String>()
            for (old in olds) {
                val name = names[old]?.lowercase() ?: continue
                if (isReadable(old)) continue
                val target = cands[name]?.firstOrNull { it.uri != old && isReadable(it.uri) } ?: continue
                map[old] = target.uri
            }
            withContext(Dispatchers.Main) {
                applyRemap(map)
                relinkMessage = if (map.isEmpty()) "Aucun fichier correspondant n'a été trouvé."
                else "${map.size} fichier(s) relié(s)."
                checkMissing()
            }
        }
    }

    private fun applyRemap(map: Map<String, String>) {
        if (map.isEmpty()) return
        fun re(u: String) = map[u] ?: u
        imported = imported.map { if (map.containsKey(it.uri)) it.copy(uri = map.getValue(it.uri)) else it }.distinctBy { it.uri }
        stats = stats.entries.associate { (k, v) ->
            val b = baseUri(k)
            (map[b]?.let { n -> n + k.substring(b.length) } ?: k) to v
        }
        meta = meta.entries.associate { (k, v) -> re(k) to v }
        routines = routines.map { r -> r.copy(items = r.items.map { it.copy(uri = re(it.uri)) }) }
        saveImported(); saveStats(); saveMeta(); saveRoutines()
    }

    fun relinkFromTree(tree: Uri) {
        runCatching { cr.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        viewModelScope.launch(Dispatchers.IO) {
            val found = scanTree(tree)
            withContext(Dispatchers.Main) { relink(found) }
        }
    }

    fun relinkFromDocs(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val found = uris.map { u ->
                runCatching { cr.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val name = displayName(u)
                val mime = cr.getType(u) ?: ""
                Exercise(u.toString(), name, mime == "application/pdf" || name.endsWith(".pdf", true))
            }
            withContext(Dispatchers.Main) { relink(found) }
        }
    }

    /** Relie avec ce qui est déjà dans la bibliothèque (dossier ou album choisi à nouveau). */
    fun relinkFromLibrary() = relink(emptyList())

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

    // ------------------------------------------------------------ statistiques

    fun metaPage(uri: String): Int = meta[uri]?.lastPage ?: 0

    fun lastOpened(uri: String): Long = meta[uri]?.lastOpened ?: 0L

    /** Statistiques affichées dans la galerie : celles de la page ouverte en dernier. */
    fun fileStats(uri: String): ExStats? = stats[statKey(uri, metaPage(uri))]

    private fun putStats(key: String, f: (ExStats) -> ExStats) {
        val cur = stats[key] ?: ExStats()
        val next = f(cur)
        if (next == cur) return
        stats = stats + (key to next)
        saveStats()
    }

    fun markOpened(uri: String) {
        val cur = meta[uri] ?: FileMeta()
        meta = meta + (uri to cur.copy(lastOpened = System.currentTimeMillis()))
        saveMeta()
    }

    fun setLastPage(uri: String, page: Int) {
        val cur = meta[uri] ?: FileMeta()
        if (cur.lastPage == page) return
        meta = meta + (uri to cur.copy(lastPage = page))
        saveMeta()
    }

    /** Appelé pendant que le métronome joue sur cet exercice (ou cette page). */
    fun recordBpm(key: String, bpm: Int) =
        putStats(key) { it.copy(firstBpm = it.firstBpm ?: bpm, lastBpm = bpm) }

    fun updateFirstBpm(key: String, bpm: Int) = putStats(key) { it.copy(firstBpm = bpm) }

    fun updateTarget(key: String, bpm: Int?) = putStats(key) { it.copy(targetBpm = bpm) }

    fun resetStats(uri: String) {
        stats = stats.filterKeys { it != uri && !it.startsWith("$uri#p") }
        meta = meta - uri
        saveStats()
        saveMeta()
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

    fun addToRoutine(id: String, uris: List<String>) =
        updateRoutine(id) { it.copy(items = it.items + uris.map { u -> RoutineItem(u) }) }

    fun removeFromRoutine(id: String, index: Int) = updateRoutine(id) {
        it.copy(items = it.items.toMutableList().also { l -> if (index in l.indices) l.removeAt(index) })
    }

    fun moveInRoutine(id: String, index: Int, delta: Int) = updateRoutine(id) { r ->
        val to = index + delta
        if (index !in r.items.indices || to !in r.items.indices) r
        else r.copy(items = r.items.toMutableList().also { l -> l.add(to, l.removeAt(index)) })
    }

    fun updateItemMinutes(id: String, index: Int, minutes: Int) = updateRoutine(id) { r ->
        if (index !in r.items.indices) r
        else r.copy(items = r.items.toMutableList().also { l ->
            l[index] = l[index].copy(minutes = minutes.coerceIn(0, 600))
        })
    }

    fun updateAutoAdvance(id: String, v: Boolean) = updateRoutine(id) { it.copy(autoAdvance = v) }

    // ------------------------------------------------------------ temps d'utilisation quotidien

    /** Le temps compte tant que l'appli est à l'écran ou que le métronome joue. */
    fun onForegroundChanged(fg: Boolean) {
        foreground = fg
        if (fg) refreshFolder()
        refreshToday()
        if (!fg) saveUsage()
    }

    private fun refreshToday() {
        val arr = usageDays[dayKey()]
        todayAppSec = (arr?.get(0) ?: 0L) / 1000
        todayPlaySec = (arr?.get(1) ?: 0L) / 1000
    }

    private fun startUsageLoop() {
        viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            var n = 0
            while (true) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val dt = (now - last).coerceIn(0L, 5000L)
                last = now
                val playing = playingProvider()
                if (foreground || playing) {
                    val arr = usageDays.getOrPut(dayKey()) { LongArray(2) }
                    arr[0] += dt
                    if (playing) arr[1] += dt
                }
                refreshToday()
                n++
                if (n % 15 == 0) saveUsage()
            }
        }
    }

    private fun loadUsage() {
        runCatching {
            val o = JSONObject(prefs.getString("usage", "{}") ?: "{}")
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                usageDays[k] = longArrayOf(a.getLong(0), a.getLong(1))
            }
        }
        refreshToday()
    }

    private fun saveUsage() {
        val keys = usageDays.keys.sortedDescending().take(120)
        val o = JSONObject()
        keys.forEach { k -> usageDays[k]?.let { o.put(k, JSONArray(listOf(it[0], it[1]))) } }
        prefs.edit().putString("usage", o.toString()).apply()
    }

    override fun onCleared() { saveUsage() }

    /** Jours du plus récent au plus ancien. Lit [todayAppSec] pour que l'écran se rafraîchisse chaque seconde. */
    fun usageDaysList(): List<DayUsage> {
        val tick = todayAppSec + todayPlaySec
        return usageDays.entries
            .map { DayUsage(it.key, it.value[0] / 1000, it.value[1] / 1000) }
            .filter { it.appSec > 0 || it.playSec > 0 || tick < 0 }
            .sortedByDescending { it.day }
    }

    // ------------------------------------------------------------ ouverture

    /** Ouvre l'exercice dans la liste affichée (même tri) pour pouvoir swiper vers les autres. */
    fun openExercise(e: Exercise, list: List<Exercise> = listOf(e)) {
        val l = if (list.any { it.uri == e.uri }) list else listOf(e)
        viewerItems = l
        viewerMinutes = l.map { 0 }
        viewerAutoAdvance = false
        viewerStart = l.indexOfFirst { it.uri == e.uri }.coerceAtLeast(0)
        viewerTitle = null
        stack.add(Screen.VIEWER)
    }

    fun openRoutine(id: String, start: Int = 0) {
        val r = routines.firstOrNull { it.id == id } ?: return
        val lib = library
        val pairs = r.items.mapNotNull { ri -> lib.firstOrNull { it.uri == ri.uri }?.let { it to ri.minutes } }
        if (pairs.isEmpty()) return
        val items = pairs.map { it.first }
        viewerItems = items
        viewerMinutes = pairs.map { it.second }
        viewerAutoAdvance = r.autoAdvance
        viewerStart = start.coerceIn(0, items.lastIndex)
        viewerTitle = r.name
        stack.add(Screen.VIEWER)
    }

    fun editRoutine(id: String) {
        editingRoutineId = id
        stack.add(Screen.ROUTINE_EDIT)
    }
}
