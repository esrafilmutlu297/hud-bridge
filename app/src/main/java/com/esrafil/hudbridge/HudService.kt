package com.esrafil.hudbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder

/** Telefonun GPS hızını okuyup HUD'a gönderir. Ön plan servisi olduğu için arka planda kapanmaz. */
class HudService : Service(), LocationListener {

    private var lm: LocationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.init(applicationContext)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "HUD Köprü", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("HUD Köprü çalışıyor")
            .setContentText("Hız telefonun GPS'inden HUD'a gönderiliyor")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, n)
        }
        HudBle.connect(applicationContext)
        HudBle.setState("HIZ", "HIZ:0")
        startLocation()
        return START_STICKY
    }

    private fun startLocation() {
        val m = getSystemService(LOCATION_SERVICE) as LocationManager
        lm = m
        try {
            m.removeUpdates(this)
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
            AppLog.add("GPS hız okuma başladı")
        } catch (e: SecurityException) {
            AppLog.add("Konum izni yok")
        } catch (e: Exception) {
            AppLog.add("GPS hatası: ${e.message}")
        }
    }

    override fun onLocationChanged(loc: Location) {
        if (!loc.hasSpeed()) return
        val kmh = Math.round(loc.speed * 3.6f)
        val v = if (kmh < 2) 0 else kmh
        HudBle.setState("HIZ", "HIZ:$v")
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "hud_bridge"
    }
}
