package com.ydeclik.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MetronomeViewModel(private val app: Application) : AndroidViewModel(app) {

    private val engine = ClickEngine()
    private val prefs = app.getSharedPreferences("ydeclik", Context.MODE_PRIVATE)
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val wakeLock = app.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ydeclik:playback")
    private var tickJob: Job? = null
    private val taps = ArrayList<Long>()

    // Un appel entrant (ou toute autre appli qui prend le focus audio) arrête le métronome.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
    }
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
        .build()
    private var modeWatcher: ModeWatcher? = null

    /** Vrai pendant la saisie d'un nombre (les raccourcis du clavier physique sont alors ignorés). */
    var typing = false

    var cfg by mutableStateOf(Config()); private set
    var isPlaying by mutableStateOf(false); private set
    var currentBeat by mutableIntStateOf(-1); private set
    var currentSub by mutableIntStateOf(-1); private set
    var flash by mutableStateOf(false); private set
    var flashAccent by mutableStateOf(false); private set
    var tempoEditTrigger by mutableIntStateOf(0); private set

    var pan by mutableFloatStateOf(0f); private set
    var sound by mutableStateOf(SoundKind.CLICK); private set
    var lightEnabled by mutableStateOf(true); private set
    var deviceName by mutableStateOf(""); private set
    var devices by mutableStateOf(emptyList<AudioDeviceInfo>()); private set

    var presets by mutableStateOf(List<Config?>(9) { null }); private set
    var currentSlot by mutableIntStateOf(0); private set

    init {
        prefs.getString("cfg", null)?.let { s ->
            runCatching { configFromJson(JSONObject(s)) }.getOrNull()?.let { cfg = it }
        }
        prefs.getString("presets", null)?.let { s ->
            runCatching {
                val a = JSONArray(s)
                if (a.length() == 9) {
                    presets = List(9) { i -> a.optJSONObject(i)?.let { configFromJson(it) } }
                }
            }
        }
        pan = 0f   // la balance gauche/droite a été retirée
        lightEnabled = prefs.getBoolean("light", true)
        currentSlot = prefs.getInt("slot", 0).coerceIn(0, 8)
        prefs.getString("sound", null)?.let { n ->
            SoundKind.values().firstOrNull { it.name == n }?.let { sound = it }
        }
        deviceName = prefs.getString("device", "") ?: ""

        engine.onTempoChange = { setBPM(it) }
        engine.cfg.set(cfg)
        engine.setSound(sound)
        engine.pan = pan
        refreshDevices()

        if (Build.VERSION.SDK_INT >= 31) {
            modeWatcher = ModeWatcher(app) { stop() }.also { it.start() }
        }
    }

    // ------------------------------------------------------------ config plumbing

    /** Configuration de l'écran Métronome, mise de côté pendant qu'on est dans un exercice (réglages propres à la page). */
    private var mainCfg: Config? = null

    fun enterPageMode() { if (mainCfg == null) mainCfg = cfg }

    fun leavePageMode() {
        val m = mainCfg ?: return
        mainCfg = null
        update(m.copy(bpm = cfg.bpm))   // le BPM reste commun aux deux écrans
    }

    private fun update(c: Config) {
        cfg = c
        engine.cfg.set(c)
        val saved = mainCfg?.copy(bpm = c.bpm) ?: c
        prefs.edit().putString("cfg", saved.toJson().toString()).apply()
    }

    // ------------------------------------------------------------ transport

    /** Applique la configuration enregistrée pour une page (ou la configuration par défaut). */
    fun applyPageConfig(c: Config) { if (c.isConsistent()) update(c) }

    fun toggle() { if (isPlaying) stop() else start() }

    fun start() {
        if (isPlaying) return
        runCatching { audioManager.requestAudioFocus(focusRequest) }
        engine.start()
        isPlaying = true
        if (!wakeLock.isHeld) wakeLock.acquire(6 * 60 * 60 * 1000L)
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, PlaybackService::class.java))
        }
        tickJob = viewModelScope.launch {
            while (isActive) { tick(); delay(8) }
        }
    }

    fun stop() {
        if (!isPlaying) return
        engine.stop()
        isPlaying = false
        tickJob?.cancel(); tickJob = null
        currentBeat = -1; currentSub = -1; flash = false
        if (wakeLock.isHeld) wakeLock.release()
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        app.stopService(Intent(app, PlaybackService::class.java))
    }

    private fun tick() {
        val evs = engine.drain(engine.currentFrame())
        val last = evs.lastOrNull() ?: return
        currentBeat = last.beat
        currentSub = last.sub
        for (e in evs) {
            if (e.sub == 0 && !e.muted && e.state != NoteState.REST && lightEnabled) {
                flashAccent = e.state == NoteState.ACCENT
                flash = true
                viewModelScope.launch { delay(90); flash = false }
            }
        }
    }

    override fun onCleared() {
        engine.stop()
        if (wakeLock.isHeld) wakeLock.release()
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        modeWatcher?.stop()
        app.stopService(Intent(app, PlaybackService::class.java))
    }

    // ------------------------------------------------------------ tempo

    fun setBPM(v: Int) {
        val c = v.coerceIn(20, 300)
        if (c != cfg.bpm) update(cfg.copy(bpm = c))
    }

    fun nudge(d: Int) = setBPM(cfg.bpm + d)

    /** Rejoint le multiple de 5 le plus proche dans la direction donnée, puis avance de 5 en 5. */
    fun step5(dir: Int) {
        val b = cfg.bpm
        val r = b % 5
        when {
            r == 0 -> setBPM(b + 5 * dir)
            dir > 0 -> setBPM(b + (5 - r))
            else -> setBPM(b - r)
        }
    }

    fun tap() {
        val now = SystemClock.elapsedRealtime()
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        taps.add(now)
        if (taps.size > 8) taps.removeAt(0)
        if (taps.size >= 3) {
            val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
            if (avg > 0) setBPM((60000.0 / avg).toInt())
        }
    }

    // ------------------------------------------------------------ pattern editing

    fun setSig(s: TimeSig) = update(cfg.copy(sig = s, grouping = 0).resetPattern())

    fun cycleGrouping() {
        val n = (cfg.grouping + 1) % maxOf(1, cfg.sig.groupings.size)
        update(cfg.copy(grouping = n).resetPattern(keepSubs = true))
    }

    fun cycleSub(b: Int) {
        if (b !in cfg.subs.indices || b !in cfg.states.indices) return
        val n = cfg.subs[b] % 4 + 1            // 1 -> 2 -> 3 -> 4 -> 1
        val subs = cfg.subs.toMutableList().also { it[b] = n }
        val states = cfg.states.toMutableList()
        val row = states[b].take(n).toMutableList()
        while (row.size < n) row.add(NoteState.NORMAL)
        states[b] = row
        update(cfg.copy(subs = subs, states = states))
    }

    fun toggleNote(b: Int, k: Int, s: NoteState) {
        val row = cfg.states.getOrNull(b) ?: return
        if (k !in row.indices) return
        val newRow = row.toMutableList().also { it[k] = if (it[k] == s) NoteState.NORMAL else s }
        val states = cfg.states.toMutableList().also { it[b] = newRow }
        update(cfg.copy(states = states))
    }

    /** Applique un réglage complet (utilisé par les setlists). */
    fun applyPattern(bpm: Int, sig: TimeSig, grouping: Int, subs: List<Int>, states: List<List<NoteState>>) {
        update(cfg.copy(bpm = bpm.coerceIn(20, 300), sig = sig, grouping = grouping, subs = subs, states = states))
    }

    // ------------------------------------------------------------ auto mode

    fun toggleAuto() = update(cfg.copy(autoOn = !cfg.autoOn))
    fun toggleAutoMode() =
        update(cfg.copy(autoMode = if (cfg.autoMode == AutoMode.MUTE) AutoMode.ADD else AutoMode.MUTE))
    fun toggleMuteRandom() = update(cfg.copy(muteRandom = !cfg.muteRandom))
    fun toggleAddUnit() =
        update(cfg.copy(addUnit = if (cfg.addUnit == AddUnit.BAR) AddUnit.SEC else AddUnit.BAR))
    fun setPlayBars(v: Int) = update(cfg.copy(playBars = v))
    fun setMuteBars(v: Int) = update(cfg.copy(muteBars = v))
    fun setAddBpm(v: Int) = update(cfg.copy(addBpm = v))
    fun setAddEvery(v: Int) = update(cfg.copy(addEvery = v))

    // ------------------------------------------------------------ presets (9 emplacements)

    private fun savePresets() {
        val arr = JSONArray()
        presets.forEach { arr.put(it?.toJson() ?: JSONObject.NULL) }
        prefs.edit().putString("presets", arr.toString()).apply()
    }

    fun savePreset(i: Int) {
        if (i !in 0..8) return
        currentSlot = i
        prefs.edit().putInt("slot", i).apply()
        presets = presets.toMutableList().also { it[i] = cfg.copy(autoOn = false) }
        savePresets()
    }

    fun loadPreset(i: Int) {
        if (i !in 0..8) return
        currentSlot = i
        prefs.edit().putInt("slot", i).apply()
        val p = presets[i] ?: return
        update(cfg.copy(bpm = p.bpm, sig = p.sig, grouping = p.grouping, subs = p.subs, states = p.states))
    }

    fun cyclePreset() {
        for (off in 1..9) {
            val i = (currentSlot + off) % 9
            if (presets[i] != null) { loadPreset(i); return }
        }
    }

    // ------------------------------------------------------------ son et réglages

    fun cycleSound() {
        val all = SoundKind.values()
        setSoundKind(all[(sound.ordinal + 1) % all.size])
    }

    fun setSoundKind(s: SoundKind) {
        sound = s
        engine.setSound(s)
        prefs.edit().putString("sound", s.name).apply()
    }

    fun updatePan(p: Float) {
        pan = p.coerceIn(-1f, 1f)
        engine.pan = pan
        prefs.edit().putFloat("pan", pan).apply()
    }

    fun toggleLight() {
        lightEnabled = !lightEnabled
        prefs.edit().putBoolean("light", lightEnabled).apply()
    }

    fun deviceLabel(d: AudioDeviceInfo): String {
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Haut-parleur"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Filaire"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> "Autre"
        }
        return "${d.productName} ($type)"
    }

    fun refreshDevices() {
        devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        engine.setDevice(devices.firstOrNull { deviceLabel(it) == deviceName })
    }

    fun setOutputDevice(label: String) {
        deviceName = label
        prefs.edit().putString("device", label).apply()
        engine.setDevice(devices.firstOrNull { deviceLabel(it) == label })
    }

    // ------------------------------------------------------------ clavier physique

    fun handleKey(keyCode: Int, ctrl: Boolean, repeat: Boolean): Boolean {
        if (typing) return false
        fun once(f: () -> Unit): Boolean { if (!repeat) f(); return true }

        if (!ctrl) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { nudge(1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { nudge(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { step5(1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { step5(-1); return true }
            }
        }
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            val slot = keyCode - KeyEvent.KEYCODE_1
            return once { if (ctrl) savePreset(slot) else loadPreset(slot) }
        }
        if (ctrl) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> once { tempoEditTrigger++ }
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_T -> once { tap() }
            KeyEvent.KEYCODE_SPACE -> once { toggle() }
            KeyEvent.KEYCODE_A -> once { toggleAuto() }
            KeyEvent.KEYCODE_O -> once { cycleSound() }
            KeyEvent.KEYCODE_S -> once { savePreset(currentSlot) }
            KeyEvent.KEYCODE_P -> once { cyclePreset() }
            KeyEvent.KEYCODE_L -> once { toggleLight() }
            else -> false
        }
    }
}

/** Détecte la sonnerie / un appel en cours (Android 12+) et arrête le métronome. */
@RequiresApi(31)
private class ModeWatcher(private val ctx: Context, private val onCall: () -> Unit) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val listener = AudioManager.OnModeChangedListener { mode ->
        if (mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION
        ) onCall()
    }

    fun start() = am.addOnModeChangedListener(ContextCompat.getMainExecutor(ctx), listener)
    fun stop() = am.removeOnModeChangedListener(listener)
}
