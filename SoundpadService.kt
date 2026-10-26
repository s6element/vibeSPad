package app.soundpad

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings

class SoundpadService : Service() {
    private var overlay: Overlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        inst = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sp", "Soundpad", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "sp")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Soundpad")
            .setContentText("работает")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        sync()
        return START_NOT_STICKY
    }

    fun sync() {
        if (Store.overlay && Settings.canDrawOverlays(this)) {
            if (overlay == null) {
                overlay = Overlay(this).also { it.show() }
            }
        } else {
            overlay?.hide()
            overlay = null
        }
        if (!Store.overlay && !Engine.injecting) stopSelf()
    }

    override fun onDestroy() {
        overlay?.hide()
        overlay = null
        inst = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var inst: SoundpadService? = null

        fun poke(c: Context) {
            val need = Store.overlay || Engine.injecting
            val s = inst
            if (s != null) {
                s.sync()
            } else if (need && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                c.startForegroundService(Intent(c, SoundpadService::class.java))
            }
        }
    }
}
