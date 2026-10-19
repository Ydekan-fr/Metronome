package com.ydeclik.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
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

    /** True while a number is being typed (hardware-keyboard shortcuts are then ignored). */
    var typing = false

    var cfg by mutableStateOf(Config()); private set
    var isPlaying by mutableStateOf(false); private set
    var currentBeat by mutableIntStateOf(-1); private set
    var currentSub by mutableIntStateOf(-1); private set
    var flash by mutableStateOf(false); private set
    var flashAccent by mutableStateOf(false); private set
    var tempoEditTrigger by mutableIntStateOf(0); private set

    var volume by mutableFloatStateOf(0.8f); private set
    var muted by mutableStateOf(false); private set
    var pan by mutableFloatStateOf(0f); private set
    var sound by mutableStateOf(SoundKind.CLICK); private set
    var lightEnabled by mutableStateOf(true); private set
    var keepScreenOn by mutableStateOf(true); private set
    var fullscreen by mutableStateOf(false); private set
    var deviceName by mutableStateOf(""); private set
    var devices by mutableStateOf(emptyList<AudioDeviceInfo>()); private set

    var presets by mutableStateOf(List<Config?>(9) { null }); private set
    var currentSlot by mutableIntStateOf(0); private set

    init {
        // Restore saved state
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
        volume = prefs.getFloat("volume", 0.8f)
        pan = prefs.getFloat("pan", 0f)
        lightEnabled = prefs.getBoolean("light", true)
        keepScreenOn = prefs.getBoolean("keepScreen", true)
        currentSlot = prefs.getInt("slot", 0).coerceIn(0, 8)
        prefs.getString("sound", null)?.let { n ->
            SoundKind.values().firstOrNull { it.name == n }?.let { sound = it }
        }
        deviceName = prefs.getString("device", "") ?: ""

        engine.onTempoChange = { setBPM(it) }
        engine.cfg.set(cfg)
        engine.setSound(sound)
        engine.setVolume(volume)
        engine.pan = pan
        refreshDevices()
    }

    // ------------------------------------------------------------ config plumbing

    private fun update(c: Config) {
        cfg = c
        engine.cfg.set(c)
        prefs.edit().putString("cfg", c.toJson().toString()).apply()
    }

    // ------------------------------------------------------------ transport

    fun toggle() { if (isPlaying) stop() else start() }

    fun start() {
        if (isPlaying) return
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
        app.stopService(Intent(app, PlaybackService::class.java))
    }

    // ------------------------------------------------------------ tempo

    fun setBPM(v: Int) {
        val c = v.coerceIn(20, 300)
        if (c != cfg.bpm) update(cfg.copy(bpm = c))
    }

    fun nudge(d: Int) = setBPM(cfg.bpm + d)

    /** Snap to the nearest multiple of 5 in the given direction first, then step by 5. */
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

    // ------------------------------------------------------------ auto mode

    fun toggleAuto() = update(cfg.copy(autoOn = !cfg.autoOn))
    fun toggleAutoMode() =
        update(cfg.copy(autoMode = if (cfg.autoMode == AutoMode.MUTE) AutoMode.ADD else AutoMode.MUTE))
    fun toggleAddUnit() =
        update(cfg.copy(addUnit = if (cfg.addUnit == AddUnit.BAR) AddUnit.SEC else AddUnit.BAR))
    fun setPlayBars(v: Int) = update(cfg.copy(playBars = v))
    fun setMuteBars(v: Int) = update(cfg.copy(muteBars = v))
    fun setAddBpm(v: Int) = update(cfg.copy(addBpm = v))
    fun setAddEvery(v: Int) = update(cfg.copy(addEvery = v))

    // ------------------------------------------------------------ presets

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

    // ------------------------------------------------------------ sound & settings

    fun cycleSound() {
        val all = SoundKind.values()
        setSoundKind(all[(sound.ordinal + 1) % all.size])
    }

    fun setSoundKind(s: SoundKind) {
        sound = s
        engine.setSound(s)
        prefs.edit().putString("sound", s.name).apply()
    }

    private fun applyVolume() = engine.setVolume(if (muted) 0f else volume)

    fun updateVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        applyVolume()
        prefs.edit().putFloat("volume", volume).apply()
    }

    fun changeVolume(d: Float) = updateVolume(volume + d)

    fun toggleMute() { muted = !muted; applyVolume() }

    fun updatePan(p: Float) {
        pan = p.coerceIn(-1f, 1f)
        engine.pan = pan
        prefs.edit().putFloat("pan", pan).apply()
    }

    fun toggleLight() {
        lightEnabled = !lightEnabled
        prefs.edit().putBoolean("light", lightEnabled).apply()
    }

    fun updateKeepScreenOn(v: Boolean) {
        keepScreenOn = v
        prefs.edit().putBoolean("keepScreen", v).apply()
    }

    fun toggleFullscreen() { fullscreen = !fullscreen }

    fun deviceLabel(d: AudioDeviceInfo): String {
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            else -> "Other"
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

    // ------------------------------------------------------------ hardware keyboard

    fun handleKey(keyCode: Int, ctrl: Boolean, repeat: Boolean): Boolean {
        if (typing) return false
        fun once(f: () -> Unit): Boolean { if (!repeat) f(); return true }

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (ctrl) changeVolume(0.05f) else nudge(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (ctrl) changeVolume(-0.05f) else nudge(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (ctrl) changeVolume(0.05f) else step5(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (ctrl) changeVolume(-0.05f) else step5(-1); return true }
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
            KeyEvent.KEYCODE_M -> once { toggleMute() }
            KeyEvent.KEYCODE_O -> once { cycleSound() }
            KeyEvent.KEYCODE_S -> once { savePreset(currentSlot) }
            KeyEvent.KEYCODE_P -> once { cyclePreset() }
            KeyEvent.KEYCODE_L -> once { toggleLight() }
            KeyEvent.KEYCODE_F -> once { toggleFullscreen() }
            else -> false
        }
    }
}
