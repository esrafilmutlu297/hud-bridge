package com.esrafil.hudbridge

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Google Maps (ve Saphe) bildirimlerini okur, HUD'a yollar. */
class NavListener : NotificationListenerService() {

    private val distRe = Regex("""^\s*(\d+(?:[.,]\d+)?)\s*(km|m|ft|mi|yd)\b""", RegexOption.IGNORE_CASE)
    private val exitRe = Regex("""(\d+)\s*\.\s*çıkış|(\d+)(?:st|nd|rd|th)\s+exit|(\d+)\.\s*Ausfahrt""", RegexOption.IGNORE_CASE)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clearWarn = Runnable { HudBle.setState("UYARI", "UYARI:YOK") }
    private val warnDistRe = Regex("""(\d+(?:[.,]\d+)?)\s*(km|kilomet\w*|meters?|metres?|m|metre)\b""", RegexOption.IGNORE_CASE)
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
        if (sbn.packageName?.contains("saphe", true) == true) {
            lastOtherKey.remove(sbn.packageName)
            if (sbn.notification.channelId == "alarmNotificationChannel") {
                handler.removeCallbacks(clearWarn)
                HudBle.setState("UYARI", "UYARI:YOK")
            }
        }
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
        // Küçük resim her zaman aynı genel "navigasyon" simgesi; sadece büyük resim dönüş okudur
        val icons = listOf("big" to n.getLargeIcon())
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

        // Mesafeyi metreye çevir; dönüş uzaksa düz ok göster, yaklaşınca asıl dönüş okunu
        val dm = distRe.find(title)
        var meters = 0.0
        if (dm != null) {
            val num = dm.groupValues[1].replace(',', '.').toDoubleOrNull() ?: 0.0
            val unit = dm.groupValues[2].lowercase()
            meters = when (unit) {
                "km" -> num * 1000.0
                "ft" -> num * 0.3048
                "mi" -> num * 1609.344
                "yd" -> num * 0.9144
                else -> num
            }
            HudBle.setState("MESAFE", "MESAFE:${dm.groupValues[1]}$unit")
        }
        // Kavşak: yazıda çıkış numarası varsa onu kullan ("3. çıkışa girin"), yoksa ikonun şablonu
        var yon = label
        val em = exitRe.find(text)
        if (em != null) {
            val n = em.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 2
            yon = "KAVSAK${n.coerceIn(1, 4)}"
        }
        if (meters > FAR_METERS) {
            HudBle.setState("YON", "YON:DUZ")
        } else if (yon != null) {
            HudBle.setState("YON", "YON:$yon")
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
        // Uyarıyı her zaman işle; sadece günlük kaydı tekrarlarda atlanır
        handleSaphe(n.channelId, ex)
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

    /** Saphe uyarı bildirimi -> HUD uyarı grafiği. Radar/kamera uyarıları ayarda kapalıysa HUD'a gitmez. */
    private fun handleSaphe(channel: String?, ex: Bundle) {
        if (channel != "alarmNotificationChannel") return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val t = title.lowercase()
        if (t.contains("speed limit")) return
        val type = when {
            Regex("camera|blitzer|radar|speed trap|kamera").containsMatchIn(t) -> "CAM"
            Regex("accident|crash|unfall|kaza").containsMatchIn(t) -> "CRASH"
            Regex("animal|wildlife|tier|hayvan").containsMatchIn(t) -> "ANIMAL"
            Regex("stationary|broken|breakdown|panne|stehend").containsMatchIn(t) -> "CARSHOLD"
            else -> "WARN"
        }
        AppLog.add("Saphe uyarısı: '$title' -> $type")
        val dm = warnDistRe.find(t)
        if (dm != null) {
            val unit = if (dm.groupValues[2].startsWith("k")) "km" else "m"
            HudBle.setState("RMESAFE", "RMESAFE:${dm.groupValues[1]}$unit")
        }
        HudBle.setState("UYARI", "UYARI:$type")
        // Silinme bildirimi gelmezse 30 sn sonra kendiliğinden temizle
        handler.removeCallbacks(clearWarn)
        handler.postDelayed(clearWarn, 30_000L)
    }

    companion object {
        const val MAPS = "com.google.android.apps.maps"
        /** Dönüşe bu mesafeden uzaksa HUD'da düz ok gösterilir (metre). */
        const val FAR_METERS = 1000.0
    }
}
