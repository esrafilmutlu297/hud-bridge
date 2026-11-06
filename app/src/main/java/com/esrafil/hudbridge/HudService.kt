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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/** Telefonun GPS hızını okuyup HUD'a gönderir. Ön plan servisi olduğu için arka planda kapanmaz. */
class HudService : Service(), LocationListener {

    private var lm: LocationManager? = null
    private var lastLoc: Location? = null
    private val prevByProvider = HashMap<String, Location>()
    private var lastGpsAt = 0L
    private var lastLocAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val limitTick = object : Runnable {
        override fun run() {
            val l = lastLoc
            // Konum 15 saniyeden eskiyse sorgulama
            if (l != null && (SystemClock.elapsedRealtime() - lastLocAt) < 15_000L) {
                OsmLimit.update(l)
            }
            handler.postDelayed(this, 10_000L)
        }
    }

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
            val provs = ArrayList<String>()
            provs.add(LocationManager.GPS_PROVIDER)
            provs.add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) provs.add(LocationManager.FUSED_PROVIDER)
            val all = m.allProviders
            var n = 0
            for (p in provs) {
                if (!all.contains(p)) continue
                try {
                    m.requestLocationUpdates(p, 1000L, 0f, this)
                    n++
                } catch (e: SecurityException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.add("Konum kaynağı hatası ($p): ${e.message}")
                }
            }
            AppLog.add("Konum okuma başladı, kaynak sayısı: $n")
            handler.removeCallbacks(limitTick)
            handler.postDelayed(limitTick, 10_000L)
        } catch (e: SecurityException) {
            AppLog.add("Konum izni yok")
        } catch (e: Exception) {
            AppLog.add("GPS hatası: ${e.message}")
        }
    }

    override fun onLocationChanged(loc: Location) {
        val prov = loc.provider ?: ""
        val now = SystemClock.elapsedRealtime()
        // GPS varsa onu tercih et; ağ/fused (ve sahte konum) sadece GPS 5 sn sessizse kullanılır
        if (prov == LocationManager.GPS_PROVIDER) lastGpsAt = now
        else if (now - lastGpsAt < 5000L) return
        lastLoc = loc
        lastLocAt = now

        var speed = if (loc.hasSpeed()) loc.speed else -1f
        val prev = prevByProvider[prov]
        if (speed < 0f && prev != null) {
            val dt = (loc.time - prev.time) / 1000f
            if (dt > 0.5f && dt < 10f) speed = prev.distanceTo(loc) / dt
        }
        prevByProvider[prov] = loc
        if (speed < 0f) return
        val kmh = Math.round(speed * 3.6f)
        val v = if (kmh < 2) 0 else kmh
        HudBle.setState("HIZ", "HIZ:$v")
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        handler.removeCallbacks(limitTick)
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "hud_bridge"
    }
}
