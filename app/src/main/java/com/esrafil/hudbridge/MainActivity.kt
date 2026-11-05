package com.esrafil.hudbridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6)
        b.layoutParams = lp
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(applicationContext)
        ArrowMatcher.load(applicationContext)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(32), dp(16), dp(16))

        val title = TextView(this)
        title.text = "HUD Köprü"
        title.textSize = 24f
        title.gravity = Gravity.START
        root.addView(title)

        statusView = TextView(this)
        statusView.textSize = 16f
        statusView.setPadding(0, dp(8), 0, dp(8))
        root.addView(statusView)

        root.addView(button("1) Bluetooth iznini ver") { askBluetooth() })
        root.addView(button("2) Uygulama bilgisi (kısıtlı ayarlara izin ver)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        root.addView(button("3) Bildirim erişimini aç") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("4) Pil kısıtlamasını kaldır") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        root.addView(button("5) Konum ve bildirim izni ver (hız için)") { askLocation() })
        root.addView(button("Hızı başlat (telefon GPS)") { startHud() })
        root.addView(button("Hızı durdur") { stopService(Intent(this, HudService::class.java)); AppLog.add("Hız servisi durduruldu") })
        root.addView(button("HUD'a bağlan") { HudBle.connect(applicationContext) })
        root.addView(button("Bağlantıyı kes") { HudBle.disconnect() })
        root.addView(button("Test: hız 87") { HudBle.sendNow("HIZ:87") })
        root.addView(button("Test: sağ ok + 200m") {
            HudBle.sendNow("YON:SAG")
            HudBle.sendNow("MESAFE:200m")
        })
        root.addView(button("Test: ekranı temizle") { HudBle.sendNow("TEMIZLE") })

        scroll = ScrollView(this)
        logView = TextView(this)
        logView.textSize = 12f
        logView.typeface = android.graphics.Typeface.MONOSPACE
        scroll.addView(logView)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        lp.topMargin = dp(8)
        scroll.layoutParams = lp
        root.addView(scroll)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        HudBle.statusListener = { refresh() }
        AppLog.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        HudBle.statusListener = null
        AppLog.listener = null
    }

    private fun refresh() {
        val access = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        val accessOn = access.contains(packageName)
        statusView.text = "HUD: ${HudBle.status}\nBildirim erişimi: ${if (accessOn) "açık" else "KAPALI"}\n" +
            "Ok şablonu sayısı: ${ArrowMatcher.templateCount()}"
        logView.text = AppLog.text()
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun askLocation() {
        val perms = ArrayList<String>()
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 2)
    }

    private fun startHud() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            AppLog.add("Önce 5) konum izni ver")
            return
        }
        startForegroundService(Intent(this, HudService::class.java))
        AppLog.add("Hız servisi başlatıldı")
    }

    private fun askBluetooth() {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        HudBle.connect(applicationContext)
    }
}
