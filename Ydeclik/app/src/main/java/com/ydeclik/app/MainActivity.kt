package com.ydeclik.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val vm: MetronomeViewModel by viewModels()
    private val ex: ExerciseViewModel by viewModels()
    private val sl: SetlistViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // L'écran ne se met jamais en veille pendant l'utilisation de l'appli.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Les touches de volume du téléphone règlent le volume du métronome.
        volumeControlStream = AudioManager.STREAM_MUSIC

        ex.playingProvider = { vm.isPlaying }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { YdeclikTheme { YdeclikApp(vm, ex, sl) } }
    }

    override fun onStart() {
        super.onStart()
        ex.onForegroundChanged(true)
    }

    override fun onStop() {
        ex.onForegroundChanged(false)
        super.onStop()
    }

    /** Raccourcis du clavier physique (tablettes, Chromebooks, claviers Bluetooth). */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN &&
            vm.handleKey(e.keyCode, e.isCtrlPressed || e.isMetaPressed, e.repeatCount > 0)
        ) return true
        return super.dispatchKeyEvent(e)
    }
}

/** Garde le processus actif (premier plan) pendant que le métronome joue. */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("ydeclik", "Ydeclik", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "ydeclik")
            .setContentTitle("Ydeclik")
            .setContentText("Métronome en cours")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
        return START_NOT_STICKY
    }
}
