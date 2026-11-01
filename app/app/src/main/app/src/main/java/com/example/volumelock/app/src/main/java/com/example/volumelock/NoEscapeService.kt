package com.example.volumelock

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.media.*
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import android.support.v4.media.session.MediaSessionCompat
import kotlin.math.PI
import kotlin.math.sin

class NoEscapeService : Service() {

    private lateinit var am: AudioManager
    private var mp: MediaPlayer? = null
    private var tone: AudioTrack? = null
    private var session: MediaSessionCompat? = null
    private var observer: ContentObserver? = null
    private var wake: PowerManager.WakeLock? = null
    private val h = Handler(Looper.getMainLooper())

    private val keepMax = object : Runnable {
        override fun run() { forceMax(); h.postDelayed(this, 400) }
    }

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(AudioManager::class.java)
        startInForeground()
        forceMax()
        h.post(keepMax)
        watchVolumeSetting()
        startSession()
        startSound()
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vol:lock")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun forceMax() {
        intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION
        ).forEach { s ->
            try {
                val max = am.getStreamMaxVolume(s)
                if (am.getStreamVolume(s) != max) am.setStreamVolume(s, max, 0)
            } catch (_: SecurityException) {}
        }
        mp?.setVolume(1f, 1f)
    }

    private fun watchVolumeSetting() {
        val uri = Settings.System.getUriFor(
            Settings.System.VOLUME_SETTINGS[AudioManager.STREAM_MUSIC]
        )
        observer = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean) {
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) !=
                    am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) forceMax()
            }
        }
        contentResolver.registerContentObserver(uri, false, observer!!)
    }

    private fun startSession() {
        val s = MediaSessionCompat(this, "vol")
        val vp = object : VolumeProviderCompat(
            VolumeProviderCompat.VOLUME_CONTROL_FIXED, 100, 100
        ) {
            override fun onAdjustVolume(direction: Int) { forceMax() }
            override fun onSetVolume(volume: Int) { forceMax() }
        }
        s.setPlaybackToRemote(vp)
        s.isActive = true
        session = s
    }

    private fun startSound() {
        val id = resources.getIdentifier("weird", "raw", packageName)
        if (id != 0) {
            mp = MediaPlayer.create(this, id)?.apply {
                isLooping = true
                setVolume(1f, 1f)
                start()
            }
        } else {
            synthFallback()
        }
    }

    private fun synthFallback() {
        Thread {
            val sr = 44100
            val dur = 3.0
            val n = (sr * dur).toInt()
            val buf = ShortArray(n)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val f = 900.0 * (1.0 - t / dur) + 120.0 + 60.0 * sin(2 * PI * 7 * t)
                phase += 2 * PI * f / sr
                val env = 0.5 + 0.5 * sin(2 * PI * 3 * t)
                buf[i] = (sin(phase) * env * Short.MAX_VALUE * 0.9).toInt().toShort()
            }
            val tr = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            tr.write(buf, 0, n)
            tr.setLoopPoints(0, n, -1)
            tr.play()
            tone = tr
        }.start()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("vol", "Volume Lock", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notif = NotificationCompat.Builder(this, "vol")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Volume locked at maximum")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, notif)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        h.postDelayed({
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }, 300)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        observer?.let { contentResolver.unregisterContentObserver(it) }
        mp?.release()
        tone?.release()
        session?.release()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
