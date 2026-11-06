package com.esrafil.hudbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import java.security.MessageDigest

/**
 * Maps'in dönüş okunu tanır. assets/arrows klasöründeki şablon resimlerle
 * 24x24'lük siyah-beyaz maske karşılaştırır. Dosya adı: YON_sira.png (ör. SAG_1.png).
 */
object ArrowMatcher {
    private const val N = 24
    private const val MAX_DIFF = 0.08

    private class Tpl(val label: String, val mask: BooleanArray)

    private var templates: List<Tpl> = emptyList()

    fun templateCount(): Int = templates.size

    fun load(ctx: Context) {
        val list = ArrayList<Tpl>()
        val names = ctx.assets.list("arrows") ?: emptyArray()
        for (name in names) {
            if (!name.endsWith(".png", true)) continue
            val base = name.substringBefore('_').substringBefore('.').uppercase()
            // KAVSAK_2.png -> KAVSAK2 (dosyadaki sıra numarası çıkış numarasıdır)
            val label = if (base == "KAVSAK") "KAVSAK" + name.substringAfter('_').substringBefore('.') else base
            val bmp = ctx.assets.open("arrows/$name").use { BitmapFactory.decodeStream(it) } ?: continue
            list.add(Tpl(label, mask(bmp)))
        }
        templates = list
    }

    fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else size
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else size
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return bmp
    }

    fun mask(src: Bitmap): BooleanArray {
        val s = Bitmap.createScaledBitmap(src, N, N, true)
        val px = IntArray(N * N)
        s.getPixels(px, 0, N, 0, 0, N, N)
        var hasAlpha = false
        for (p in px) if ((p ushr 24) < 200) { hasAlpha = true; break }
        val out = BooleanArray(N * N)
        if (hasAlpha) {
            for (i in px.indices) out[i] = (px[i] ushr 24) > 128
        } else {
            val c = px[0]
            for (i in px.indices) out[i] = dist(px[i], c) > 90
        }
        return out
    }

    private fun dist(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)
        val dg = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)
        val db = (a and 0xff) - (b and 0xff)
        return Math.abs(dr) + Math.abs(dg) + Math.abs(db)
    }

    fun hash(m: BooleanArray): String {
        val bytes = ByteArray(m.size) { if (m[it]) 1 else 0 }
        val md = MessageDigest.getInstance("MD5").digest(bytes)
        return md.take(4).joinToString("") { "%02x".format(it) }
    }

    /** (etiket, fark) döner. Etiket null ise tanıyamadı. */
    fun match(m: BooleanArray): Pair<String?, Double> {
        var best: String? = null
        var bestD = 1.0
        for (t in templates) {
            var diff = 0
            for (i in m.indices) if (m[i] != t.mask[i]) diff++
            val d = diff.toDouble() / m.size
            if (d < bestD) { bestD = d; best = t.label }
        }
        return if (best != null && bestD <= MAX_DIFF) Pair(best, bestD) else Pair(null, bestD)
    }
}
