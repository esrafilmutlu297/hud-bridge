package com.esrafil.hudbridge

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Google Maps (ve Saphe) bildirimlerini okur, HUD'a yollar. */
class NavListener : NotificationListenerService() {

    private val distRe = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(km|m|ft|mi|yd)\b""", RegexOption.IGNORE_CASE)
    private var lastMapsKey = ""
    private var lastOtherKey = HashMap<String, String>()

    override fun onListenerConnected() {
        AppLog.init(applicationContext)
        ArrowMatcher.load(applicationContext)
        AppLog.add("Bildirim erişimi aktif. Ok şablonu: ${ArrowMatcher.templateCount()}")
        HudBle.connect(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        try {
            if (pkg == MAPS) handleMaps(sbn)
            else if (pkg.contains("saphe", true)) recordOther(sbn)
        } catch (e: Exception) {
            AppLog.add("Hata: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == MAPS && sbn.notification.category == Notification.CATEGORY_NAVIGATION) {
            lastMapsKey = ""
            HudBle.clearAll()
            AppLog.add("Navigasyon bitti, ekran temizlendi")
        }
    }

    private fun handleMaps(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.category != Notification.CATEGORY_NAVIGATION) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        // Ok resmi: önce büyük resim, olmazsa küçük resim
        var label: String? = null
        var diff = 1.0
        var note = ""
        val icons = listOf("big" to n.getLargeIcon(), "small" to n.smallIcon)
        for ((kind, icon) in icons) {
            if (icon == null) continue
            val d = icon.loadDrawable(applicationContext) ?: continue
            val bmp = ArrowMatcher.drawableToBitmap(d, 84)
            val m = ArrowMatcher.mask(bmp)
            val h = ArrowMatcher.hash(m)
            if (AppLog.saveIcon(bmp, "${kind}_$h")) note += " yeni-$kind-$h"
            if (label == null) {
                val r = ArrowMatcher.match(m)
                if (r.first != null) { label = r.first; diff = r.second }
            }
        }

        val key = "$title|$text|$sub|$label"
        if (key != lastMapsKey) {
            lastMapsKey = key
            AppLog.record("MAPS title='$title' text='$text' sub='$sub' ok=$label$note")
            AppLog.add("Maps: '$title' / '$text' / ok=${label ?: "?"}$note")
        }

        val m = distRe.find(title)
        if (m != null) {
            val num = m.groupValues[1]
            val unit = m.groupValues[2].lowercase()
            HudBle.setState("MESAFE", "MESAFE:$num$unit")
        }
        if (label != null) {
            HudBle.setState("YON", "YON:$label")
        }
    }

    @Suppress("DEPRECATION")
    private fun recordOther(sbn: StatusBarNotification) {
        val n = sbn.notification
        val ex: Bundle = n.extras
        val sb = StringBuilder()
        sb.append("PKG=").append(sbn.packageName)
        sb.append(" ch=").append(n.channelId)
        sb.append(" cat=").append(n.category)
        for (k in ex.keySet()) {
            val v = ex.get(k) ?: continue
            if (v is CharSequence || v is Number || v is Boolean) sb.append(" | ").append(k).append("=").append(v)
        }
        val s = sb.toString()
        val pkg = sbn.packageName
        if (lastOtherKey[pkg] == s) return
        lastOtherKey[pkg] = s
        AppLog.record(s)
        AppLog.add("Saphe bildirimi kaydedildi")
        for ((kind, icon) in listOf("sbig" to n.getLargeIcon(), "ssmall" to n.smallIcon)) {
            if (icon == null) continue
            val d = icon.loadDrawable(applicationContext) ?: continue
            val bmp = ArrowMatcher.drawableToBitmap(d, 84)
            AppLog.saveIcon(bmp, "${kind}_${ArrowMatcher.hash(ArrowMatcher.mask(bmp))}")
        }
    }

    companion object {
        const val MAPS = "com.google.android.apps.maps"
    }
}
