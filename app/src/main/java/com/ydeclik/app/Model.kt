package com.ydeclik.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

enum class NoteState { NORMAL, ACCENT, REST }
enum class AutoMode { MUTE, ADD }
enum class AddUnit { BAR, SEC }

data class TimeSig(val beats: Int, val unit: Int) {
    val label: String get() = "$beats/$unit"

    /** Possible groupings (5/8 and 7/8 can be cycled). */
    val groupings: List<List<Int>>
        get() = when (beats to unit) {
            5 to 8 -> listOf(listOf(2, 3), listOf(3, 2))
            7 to 8 -> listOf(listOf(2, 2, 3), listOf(3, 2, 2), listOf(2, 3, 2))
            3 to 8 -> listOf(listOf(3))
            6 to 8 -> listOf(listOf(3, 3))
            9 to 8 -> listOf(listOf(3, 3, 3))
            12 to 8 -> listOf(listOf(3, 3, 3, 3))
            else -> listOf(listOf(beats))
        }

    fun groupStarts(index: Int): Set<Int> {
        val g = groupings
        val grp = g[index.coerceIn(0, g.size - 1)]
        val s = HashSet<Int>()
        var p = 0
        for (x in grp) { s.add(p); p += x }
        return s
    }

    companion object {
        val ALL = listOf(
            TimeSig(2, 4), TimeSig(3, 4), TimeSig(4, 4), TimeSig(5, 4), TimeSig(6, 4), TimeSig(7, 4),
            TimeSig(3, 8), TimeSig(5, 8), TimeSig(6, 8), TimeSig(7, 8), TimeSig(9, 8), TimeSig(12, 8)
        )
    }
}

data class Config(
    val bpm: Int = 120,
    val sig: TimeSig = TimeSig(4, 4),
    val grouping: Int = 0,
    val subs: List<Int> = listOf(1, 1, 1, 1),
    val states: List<List<NoteState>> = listOf(
        listOf(NoteState.NORMAL), listOf(NoteState.NORMAL), listOf(NoteState.NORMAL), listOf(NoteState.NORMAL)
    ),
    // Auto mode
    val autoOn: Boolean = false,
    val autoMode: AutoMode = AutoMode.MUTE,
    val playBars: Int = 2,
    val muteBars: Int = 1,
    val muteRandom: Boolean = false,
    val addBpm: Int = 5,
    val addEvery: Int = 4,
    val addUnit: AddUnit = AddUnit.BAR
) {
    fun state(b: Int, k: Int): NoteState = states.getOrNull(b)?.getOrNull(k) ?: NoteState.NORMAL

    fun resetPattern(keepSubs: Boolean = false): Config {
        val n = sig.beats
        val s = if (!keepSubs || subs.size != n) List(n) { 1 } else subs
        val starts = sig.groupStarts(grouping)
        val st = List(n) { b ->
            List(max(1, s[b])) { k -> if (k == 0 && b != 0 && starts.size > 1 && b in starts) NoteState.ACCENT else NoteState.NORMAL }
        }
        return copy(subs = s, states = st)
    }

    fun isConsistent(): Boolean =
        subs.size == sig.beats && states.size == sig.beats &&
            subs.indices.all { states[it].size == max(1, subs[it]) }
}

fun tempoName(bpm: Int): String = when {
    bpm < 40 -> "Grave"
    bpm < 60 -> "Largo"
    bpm < 66 -> "Larghetto"
    bpm < 76 -> "Adagio"
    bpm < 108 -> "Andante"
    bpm < 120 -> "Moderato"
    bpm < 156 -> "Allegro"
    bpm < 176 -> "Vivace"
    bpm < 200 -> "Presto"
    else -> "Prestissimo"
}

// ---- JSON persistence ----

fun Config.toJson(): JSONObject = JSONObject().apply {
    put("bpm", bpm)
    put("beats", sig.beats)
    put("unit", sig.unit)
    put("grouping", grouping)
    put("subs", JSONArray(subs))
    put("states", JSONArray(states.map { row -> JSONArray(row.map { it.ordinal }) }))
    put("autoMode", autoMode.name)
    put("playBars", playBars)
    put("muteBars", muteBars)
    put("muteRandom", muteRandom)
    put("addBpm", addBpm)
    put("addEvery", addEvery)
    put("addUnit", addUnit.name)
}

fun configFromJson(o: JSONObject): Config? = try {
    val subsArr = o.getJSONArray("subs")
    val statesArr = o.getJSONArray("states")
    val c = Config(
        bpm = o.getInt("bpm").coerceIn(20, 300),
        sig = TimeSig(o.getInt("beats"), o.getInt("unit")),
        grouping = o.optInt("grouping", 0),
        subs = List(subsArr.length()) { subsArr.getInt(it) },
        states = List(statesArr.length()) { i ->
            val r = statesArr.getJSONArray(i)
            List(r.length()) { NoteState.values()[r.getInt(it).coerceIn(0, 2)] }
        },
        autoMode = AutoMode.valueOf(o.optString("autoMode", "MUTE")),
        playBars = o.optInt("playBars", 2),
        muteBars = o.optInt("muteBars", 1),
        muteRandom = o.optBoolean("muteRandom", false),
        addBpm = o.optInt("addBpm", 5),
        addEvery = o.optInt("addEvery", 4),
        addUnit = AddUnit.valueOf(o.optString("addUnit", "BAR"))
    )
    if (c.isConsistent()) c else null
} catch (e: Exception) {
    null
}
