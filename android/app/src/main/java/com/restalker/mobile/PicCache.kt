package com.restalker.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.restalker.mobile.core.Pics
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** 1.7: product pictures - downloaded once (the small version), kept in the app's cache folder, shown from there. Its own download
 *  (like a browser asks for a picture) instead of a picture library, and every failure is written down with its reason, for the
 *  diagnostics and the self-check that sends a report by itself. */
object PicCache {
    private val mem = android.util.LruCache<String, Bitmap>(80)
    /** picture address -> "ok" / "failed: why" (newest) */
    val status = ConcurrentHashMap<String, String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val fails = ConcurrentHashMap<String, Int>()
    @Volatile var retryMs = 10 * 60_000L              // (tests: shorter)
    private const val MAX_BYTES = 4_000_000
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** Tests: a different downloader (address -> bytes, or an exception). */
    @Volatile var fetch: (String) -> ByteArray = ::realFetch

    fun failures(u: String): Int = fails[u] ?: 0
    fun cached(u: String, px: Int = 240): Bitmap? = mem.get(key(u, px))
    private fun key(u: String, px: Int) = if (px == 240) u else "$u@$px"

    private fun file(u: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(u.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(RkApp.app.cacheDir, "pics").apply { mkdirs() }, "$h.img")
    }

    /** The picture (call off the main thread). null = not available now (the reason is in [status]). */
    fun get(u: String, px: Int = 240): Bitmap? {
        val k = key(u, px)
        mem.get(k)?.let { return it }
        val f = file(k)
        if (f.isFile) decode(f.readBytes(), px)?.let { mem.put(k, it); status[u] = "ok"; return it } ?: f.delete()
        if (System.currentTimeMillis() - (failedAt[k] ?: 0L) < retryMs) return null
        var why = "?"
        for (t in listOf(Pics.small(u, px), u).distinct()) {
            try {
                val b = fetch(t)
                val bm = decode(b, px)
                if (bm == null) { why = "not a picture (${b.size} bytes${sniff(b)})"; continue }
                if (bm.width < 16 || bm.height < 16) { why = "a placeholder, not the picture (${bm.width}x${bm.height})"; continue }
                val tmp = File(f.path + ".tmp"); tmp.writeBytes(b); tmp.renameTo(f)
                mem.put(k, bm); status[u] = "ok"; fails.remove(u); failedAt.remove(k)
                return bm
            } catch (e: Exception) { why = (e.message ?: e.javaClass.simpleName).take(120) }
        }
        failedAt[k] = System.currentTimeMillis()
        if (px == 240) { fails[u] = (fails[u] ?: 0) + 1; status[u] = "failed: $why" }
        return null
    }

    private fun sniff(b: ByteArray): String = if (b.size > 20 && String(b, 0, 20, Charsets.ISO_8859_1).contains("<", true)) ", a web page" else ""

    private fun decode(b: ByteArray, px: Int = 240): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0) null else {
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= px + px / 3) s *= 2
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (e: Throwable) { null }

    fun realFetch(u: String): ByteArray {
        val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        if (u.contains("amazon.com/")) c.setRequestProperty("Referer", "https://www.amazon.com/")
        try {
            val code = c.responseCode
            if (code != 200) throw java.io.IOException("the picture server answered $code")
            c.inputStream.use { ins ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16_384)
                while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > MAX_BYTES) throw java.io.IOException("too big") }
                return out.toByteArray()
            }
        } finally { c.disconnect() }
    }

    /** Tests: as if the app was opened again (the pictures kept on the phone stay). */
    fun evictMemory() = mem.evictAll()

    /** Tests: forget everything. */
    fun clear() { mem.evictAll(); status.clear(); failedAt.clear(); fails.clear(); File(RkApp.app.cacheDir, "pics").deleteRecursively() }
}
