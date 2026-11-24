package com.restalker.mobile.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** 1.1.2 (owner, 2026-11-24: "the capacity to receive thousands of users as well as send messages back to them in near zero latency"):
 *  a small WebSocket client (RFC 6455) - the app's one connection to the Restalker relay, held open, so the relay can push a line the
 *  moment it has it (until 1.1.1 the app asked once a minute). Plain Java sockets, nothing added to the app. Text messages only;
 *  pings are answered; [ping] sends a protocol ping (Cloudflare answers it itself - the relay is not woken, nothing is counted). */
class Ws(url: String, timeoutMs: Int = 20_000) {
    class Closed(message: String) : Exception(message)

    private val sock: Socket
    private val ins: InputStream
    private val out: OutputStream
    private var buf = ByteArray(0)
    private val frag = ByteArrayOutputStream()
    private val rnd = SecureRandom()
    private val sendLock = Any()
    @Volatile var open = false; private set
    @Volatile var lastRx = System.currentTimeMillis(); private set

    init {
        val u = URI(url)
        val tls = u.scheme.equals("wss", true)
        val host = u.host ?: throw Closed("no host")
        val port = if (u.port > 0) u.port else if (tls) 443 else 80
        var s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            if (tls) {
                val ss = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, host, port, true) as SSLSocket
                val p = ss.sslParameters; p.endpointIdentificationAlgorithm = "HTTPS"; ss.sslParameters = p     // (the certificate must be the relay's)
                ss.soTimeout = timeoutMs
                ss.startHandshake()
                s = ss
            }
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            sock = s; ins = s.getInputStream(); out = s.getOutputStream()
            val key = b64(ByteArray(16).also { rnd.nextBytes(it) })
            val path = (u.rawPath?.ifEmpty { "/" } ?: "/") + (if (u.rawQuery != null) "?" + u.rawQuery else "")
            out.write(("GET $path HTTP/1.1\r\nHost: $host" + (if (u.port > 0) ":$port" else "") + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\nUser-Agent: RestalkerMobile\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val head = ByteArrayOutputStream()
            var tail = 0
            while (true) {
                val b = ins.read()
                if (b < 0) throw Closed("the connection ended before the relay answered")
                head.write(b)
                tail = (tail shl 8) or b
                if (tail == 0x0D0A0D0A) break
                if (head.size() > 16_384) throw Closed("the relay's answer is too long")
            }
            val text = String(head.toByteArray(), Charsets.ISO_8859_1)
            val status = text.substringBefore("\r\n")
            if (!Regex("^HTTP/1\\.[01] 101\\b").containsMatchIn(status)) throw Closed("refused: " + status.take(60))
            val accept = Regex("(?im)^sec-websocket-accept:\\s*(\\S+)\\s*$").find(text)?.groupValues?.get(1)
            val want = b64(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1)))
            if (accept != want) throw Closed("the answer is not the relay's (wrong accept key)")
            open = true
            lastRx = System.currentTimeMillis()
        } catch (e: Exception) {
            try { s.close() } catch (x: Exception) { }
            throw if (e is Closed) e else Closed((e.javaClass.simpleName + ": " + (e.message ?: "")).take(100))
        }
    }

    private fun frame(op: Int, data: ByteArray) {
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        val o = ByteArrayOutputStream(data.size + 14)
        o.write(0x80 or op)
        val n = data.size
        when {
            n < 126 -> o.write(0x80 or n)
            n < 65_536 -> { o.write(0x80 or 126); o.write(n ushr 8); o.write(n and 0xFF) }
            else -> { o.write(0x80 or 127); for (i in 7 downTo 0) o.write(((n.toLong() ushr (8 * i)) and 0xFF).toInt()) }
        }
        o.write(mask)
        for (i in data.indices) o.write(data[i].toInt() xor mask[i and 3].toInt())
        synchronized(sendLock) { out.write(o.toByteArray()); out.flush() }
    }

    fun send(text: String) { if (!open) throw Closed("not open"); frame(0x1, text.toByteArray(Charsets.UTF_8)) }
    fun ping() { if (!open) throw Closed("not open"); frame(0x9, ByteArray(0)) }

    /** -> (opcode, final, data) of the first whole frame in the buffer (taken out of it), or null while it is not all here. */
    private fun parse(): Triple<Int, Boolean, ByteArray>? {
        val b = buf
        if (b.size < 2) return null
        var n = (b[1].toInt() and 0x7F).toLong()
        var at = 2
        if (n == 126L) { if (b.size < 4) return null; n = (((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)).toLong(); at = 4 }
        else if (n == 127L) { if (b.size < 10) return null; n = 0; for (i in 2..9) n = (n shl 8) or (b[i].toLong() and 0xFF); at = 10 }
        if (n < 0 || n > 8L * 1024 * 1024) throw Closed("message too long")
        val masked = (b[1].toInt() and 0x80) != 0
        var mask: ByteArray? = null
        if (masked) { if (b.size < at + 4) return null; mask = b.copyOfRange(at, at + 4); at += 4 }
        if (b.size < at + n.toInt()) return null
        val data = b.copyOfRange(at, at + n.toInt())
        buf = b.copyOfRange(at + n.toInt(), b.size)
        if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Triple(b[0].toInt() and 0x0F, (b[0].toInt() and 0x80) != 0, data)
    }

    /** -> the next text message, or null when nothing came within [timeoutMs]. Throws [Closed] when the connection is over. */
    fun recv(timeoutMs: Int = 30_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val chunk = ByteArray(16_384)
        while (true) {
            val f = parse()
            if (f == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                val n = try { sock.soTimeout = left.toInt().coerceAtLeast(50); ins.read(chunk) }
                        catch (e: SocketTimeoutException) { return null }
                        catch (e: Exception) { open = false; throw Closed((e.javaClass.simpleName + ": " + (e.message ?: "")).take(100)) }
                if (n < 0) { open = false; throw Closed("the connection ended") }
                buf += chunk.copyOf(n)
                continue
            }
            val (op, fin, data) = f
            lastRx = System.currentTimeMillis()
            when (op) {
                0xA -> { }                                           // (the answer to our own ping)
                0x9 -> try { frame(0xA, data) } catch (e: Exception) { }
                0x8 -> {
                    open = false
                    val code = if (data.size >= 2) ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF) else 1005
                    try { frame(0x8, data.copyOf(minOf(2, data.size))) } catch (e: Exception) { }
                    close()
                    throw Closed("closed by the relay ($code ${String(data, minOf(2, data.size), maxOf(0, data.size - 2), Charsets.UTF_8)})".trim())
                }
                0x0, 0x1, 0x2 -> {
                    frag.write(data)
                    if (fin) { val t = String(frag.toByteArray(), Charsets.UTF_8); frag.reset(); return t }
                }
            }
        }
    }

    fun close() {
        open = false
        try { sock.close() } catch (e: Exception) { }
    }

    companion object {
        private const val ABC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        /** Base64 (the phone's own is not there on every Android version the app runs on). */
        fun b64(d: ByteArray): String {
            val sb = StringBuilder((d.size + 2) / 3 * 4)
            var i = 0
            while (i < d.size) {
                val a = d[i].toInt() and 0xFF
                val b = if (i + 1 < d.size) d[i + 1].toInt() and 0xFF else -1
                val c = if (i + 2 < d.size) d[i + 2].toInt() and 0xFF else -1
                sb.append(ABC[a ushr 2]).append(ABC[((a and 3) shl 4) or (if (b < 0) 0 else b ushr 4)])
                sb.append(if (b < 0) '=' else ABC[((b and 15) shl 2) or (if (c < 0) 0 else c ushr 6)]).append(if (c < 0) '=' else ABC[c and 63])
                i += 3
            }
            return sb.toString()
        }
    }
}
