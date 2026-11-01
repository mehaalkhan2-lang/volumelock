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
        am = getSystemServic
