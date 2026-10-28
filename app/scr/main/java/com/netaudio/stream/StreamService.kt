package com.netaudio.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Serviço em primeiro plano: mantém a reprodução com a tela apagada. */
class StreamService : Service() {

    companion object {
        const val ACTION_START = "com.netaudio.stream.START"
        const val ACTION_STOP = "com.netaudio.stream.STOP"
        private const val CHANNEL_ID = "netaudio"
        @Volatile var running = false
        @Volatile var status = "Desconectado"
        val logs: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

        fun log(msg: String) {
            synchronized(logs) {
                logs.add(msg)
                while (logs.size > 40) logs.removeAt(0)
            }
        }
    }

    private var client: StreamClient? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopStream()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra("host")
        if (host == null) { stopSelf(); return START_NOT_STICKY }
        val port = intent.getIntExtra("port", 7000)
        val depth = intent.getIntExtra("depth", 8)

        startForegroundCompat(host)
        stopStream()
        acquireLocks()
        running = true
        status = "Conectando..."

        val c = StreamClient(host, port, depth, { log(it) }, { status = it })
        client = c
        Thread {
            try {
                c.run()
            } catch (e: Exception) {
                log("Erro: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (client === c) {
                    running = false
                    status = "Desconectado"
                    releaseLocks()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(host: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "NetAudio", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NetAudio")
            .setContentText("Tocando áudio de $host")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, n)
        }
    }

    private fun acquireLocks() {
        releaseLocks()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "netaudio:stream").apply { acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                   else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi = wm.createWifiLock(mode, "netaudio:wifi").apply { acquire() }
    }

    private fun releaseLocks() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Exception) {}
        wake = null; wifi = null
    }

    private fun stopStream() {
        client?.stop()
        client = null
        running = false
        status = "Desconectado"
        releaseLocks()
    }

    override fun onDestroy() {
        stopStream()
        super.onDestroy()
    }
}
