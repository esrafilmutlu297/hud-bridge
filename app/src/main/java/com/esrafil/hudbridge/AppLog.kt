package com.esrafil.hudbridge

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ekrandaki kısa kayıt + telefondaki dosya kaydı (log.txt ve icons klasörü). */
object AppLog {
    private val main = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var dir: File? = null
    var listener: (() -> Unit)? = null

    fun init(ctx: Context) {
        if (dir == null) dir = ctx.getExternalFilesDir(null)
    }

    fun add(s: String) {
        val line = fmt.format(Date()) + "  " + s
        main.post {
            lines.addLast(line)
            while (lines.size > 60) lines.removeFirst()
            listener?.invoke()
        }
    }

    fun text(): String = lines.joinToString("\n")

    /** Dosyaya ham kayıt (Maps ve Saphe bildirimlerinin içeriği). */
    @Synchronized
    fun record(s: String) {
        val d = dir ?: return
        try {
            val f = File(d, "log.txt")
            if (f.length() > 3_000_000) f.delete()
            f.appendText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "  " + s + "\n")
        } catch (_: Exception) {
        }
    }

    /** Ok resimlerini kaydeder; aynı isimde dosya varsa atlar. true döner: yeni kaydedildi. */
    @Synchronized
    fun saveIcon(bmp: Bitmap, name: String): Boolean {
        val d = dir ?: return false
        return try {
            val folder = File(d, "icons")
            folder.mkdirs()
            val f = File(folder, "$name.png")
            if (f.exists()) return false
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (_: Exception) {
            false
        }
    }
}
