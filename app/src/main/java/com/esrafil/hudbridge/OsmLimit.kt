package com.esrafil.hudbridge

import android.location.Location
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bulunduğun yolun hız limitini OpenStreetMap verisinden bulur.
 * Çevredeki yolları (yaklaşık 1 km) bir kerede indirip telefonda saklar,
 * her çağrıda en yakın yolu telefonda hesaplar. Sadece yaklaşık 400 m
 * uzaklaşınca yeniden indirir. Rota hesaplamaz.
 */
object OsmLimit {
    private class Way(val limit: Int?, val lat: DoubleArray, val lon: DoubleArray)

    @Volatile private var ways: List<Way> = emptyList()
    @Volatile private var hasCache = false
    private var cLat = 0.0
    private var cLon = 0.0
    private const val HALF_DEG = 0.0045 // yaklaşık 500 m
    private const val REFETCH_M = 250.0
    private val SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.openstreetmap.fr/api/interpreter"
    )
    @Volatile private var failUntil = 0L
    @Volatile private var t0 = 0L
    private val busy = AtomicBoolean(false)

    /** Arka planda çalışır; sonucu HUD'a LIMIT:n veya LIMIT:OFF olarak gönderir. */
    fun update(loc: Location) {
        if (!busy.compareAndSet(false, true)) return
        Thread {
            try {
                if ((!hasCache || meters(loc.latitude, loc.longitude, cLat, cLon) > REFETCH_M) &&
                    System.currentTimeMillis() > failUntil) {
                    try {
                        fetch(loc.latitude, loc.longitude)
                    } catch (e: Exception) {
                        AppLog.add("OSM indirme hatası: ${e.message}")
                        failUntil = System.currentTimeMillis() + 15_000L
                    }
                }
                val v = if (hasCache) lookup(loc) else null
                HudBle.setState("LIMIT", if (v != null) "LIMIT:$v" else "LIMIT:OFF")
                AppLog.add("OSM limit: ${v ?: "yok"}")
            } catch (e: Exception) {
                AppLog.add("OSM hata: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                busy.set(false)
            }
        }.start()
    }

    private fun fetch(lat: Double, lon: Double) {
        t0 = System.currentTimeMillis()
        val types = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|" +
            "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link"
        val dLat = HALF_DEG
        val dLon = HALF_DEG / Math.cos(Math.toRadians(lat))
        val bbox = "${lat - dLat},${lon - dLon},${lat + dLat},${lon + dLon}"
        // Kutu (bbox) sorgusu, "around" sorgusundan çok daha hafif ve hızlı
        val q = "[out:json][timeout:20][bbox:$bbox];way[\"highway\"~\"^($types)\$\"];out tags geom qt;"
        var lastErr: Exception? = null
        for (server in SERVERS) {
            try {
                val txt = post(server, q)
                parse(txt, lat, lon)
                return
            } catch (e: Exception) {
                lastErr = e
                AppLog.add("OSM sunucu cevap vermedi: ${server.substringAfter("//").substringBefore("/")} (${e.message})")
            }
        }
        throw lastErr ?: Exception("bilinmeyen hata")
    }

    private fun post(server: String, q: String): String {
        val conn = URL(server).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 18000
            conn.setRequestProperty("User-Agent", "HUDBridge/1.0 (personal hobby project)")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(q, "UTF-8")).toByteArray()) }
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(txt: String, lat: Double, lon: Double) {
        val arr = JSONObject(txt).getJSONArray("elements")
        val list = ArrayList<Way>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val g = e.optJSONArray("geometry") ?: continue
            if (g.length() < 2) continue
            val la = DoubleArray(g.length())
            val lo = DoubleArray(g.length())
            for (j in 0 until g.length()) {
                val p = g.getJSONObject(j)
                la[j] = p.getDouble("lat")
                lo[j] = p.getDouble("lon")
            }
            val tags = e.optJSONObject("tags")
            list.add(Way(parseLimit(tags?.optString("maxspeed", "") ?: ""), la, lo))
        }
        ways = list
        cLat = lat
        cLon = lon
        hasCache = true
        AppLog.add("OSM: ${list.size} yol indirildi (${(System.currentTimeMillis() - t0) / 1000.0} sn)")
    }

    private fun parseLimit(s: String): Int? {
        val t = s.trim().lowercase()
        if (t.isEmpty()) return null
        val n = t.toIntOrNull()
        if (n != null) return if (n in 5..200) n else null
        return when (t) {
            "de:urban" -> 50
            "de:rural" -> 100
            "walk", "de:living_street" -> 7
            else -> null
        }
    }

    private fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val kLat = 111320.0
        val kLon = 111320.0 * Math.cos(Math.toRadians(lat1))
        val dy = (lat2 - lat1) * kLat
        val dx = (lon2 - lon1) * kLon
        return Math.sqrt(dx * dx + dy * dy)
    }

    /** Konuma en yakın yolun limiti. Gidiş yönü yolla uyuşmuyorsa o yol ceza alır. */
    private fun lookup(loc: Location): Int? {
        val kLat = 111320.0
        val kLon = 111320.0 * Math.cos(Math.toRadians(loc.latitude))
        val useHeading = loc.hasBearing() && loc.hasSpeed() && loc.speed > 3f
        var best: Way? = null
        var bestScore = 1e9
        for (w in ways) {
            for (i in 0 until w.lat.size - 1) {
                val ax = (w.lon[i] - loc.longitude) * kLon
                val ay = (w.lat[i] - loc.latitude) * kLat
                val bx = (w.lon[i + 1] - loc.longitude) * kLon
                val by = (w.lat[i + 1] - loc.latitude) * kLat
                val dx = bx - ax
                val dy = by - ay
                val len2 = dx * dx + dy * dy
                var t = if (len2 > 0.0) (-ax * dx - ay * dy) / len2 else 0.0
                if (t < 0.0) t = 0.0
                if (t > 1.0) t = 1.0
                val px = ax + t * dx
                val py = ay + t * dy
                val d = Math.sqrt(px * px + py * py)
                if (d > 40.0) continue
                var score = d
                if (useHeading && len2 > 1.0) {
                    val segB = (Math.toDegrees(Math.atan2(dx, dy)) + 360.0) % 360.0
                    var diff = Math.abs(segB - loc.bearing.toDouble()) % 360.0
                    if (diff > 180.0) diff = 360.0 - diff
                    if (diff > 90.0) diff = 180.0 - diff
                    if (diff > 45.0) score += 30.0
                }
                if (score < bestScore) { bestScore = score; best = w }
            }
        }
        return best?.limit
    }
}
