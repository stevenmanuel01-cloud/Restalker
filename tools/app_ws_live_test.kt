import com.restalker.mobile.core.Ws
fun main(args: Array<String>) {
    val base = args[0]; val rnd = java.util.Random()
    fun id() = (1..12).map { "0123456789abcdef"[rnd.nextInt(16)] }.joinToString("")
    var bad = 0
    fun say(n: String, ok: Boolean, more: String = "") { if (!ok) bad++; println((if (ok) "PASS " else "FAIL ") + n + (if (more.isNotEmpty()) "  | $more" else "")) }
    fun open(c: String): Pair<Ws, String?> { val w = Ws("$base/v1/ws?c=$c", 8000); var hello: String? = null
        while (true) { val m = w.recv(2500) ?: break; if (m.contains("\"t\":\"hello\"")) { hello = m; break } }; return w to hello }
    val (a, ha) = open(id()); val (b, hb) = open(id())
    say("1 the app's client opens a connection to a real relay and gets hello", ha != null && hb != null, (ha ?: "").take(70))
    var t = System.currentTimeMillis(); a.ping(); val none = a.recv(500)
    say("2 a protocol ping is answered below the message level", none == null && a.lastRx >= t, "${a.lastRx - t} ms")
    t = System.currentTimeMillis()
    a.send("{\"t\":\"s\",\"l\":[{\"v\":1,\"k\":\"s\",\"a\":\"" + args[1] + "\",\"s\":\"" + listOf("Target", "Walmart", "Best Buy", "GameStop")[rnd.nextInt(4)] + "\",\"st\":\"in\",\"t\":" + (System.currentTimeMillis() / 1000.0) + ",\"p\":33.5}]}")
    var ack: String? = null; while (true) { val m = a.recv(3000) ?: break; if (m.contains("\"t\":\"ok\"") || m.contains("\"t\":\"no\"")) { ack = m; break } }
    val tAck = System.currentTimeMillis() - t
    var pushed: String? = null; while (true) { val m = b.recv(1500) ?: break; if (m.contains("\"ch\":\"crowd\"") && m.contains(args[1])) { pushed = m; break } }
    say("3 a sighting sent up one connection is acknowledged", ack != null && ack.contains("\"t\":\"ok\""), "$tAck ms; ${(ack ?: "").take(50)}")
    say("4 ... and pushed to the other device at once", pushed != null, "${System.currentTimeMillis() - t} ms after sending")
    a.send("{\"t\":\"h\"}"); var here: String? = null; while (true) { val m = a.recv(3000) ?: break; if (m.contains("\"t\":\"here\"")) { here = m; break } }
    say("5 'I am here' is answered with the active count", here != null && here.contains("\"t\":\"here\"") && here.contains("\"active\":"), (here ?: "").take(60))
    val refused = try { Ws("$base/v1/ws?c=nothex", 5000); "opened" } catch (e: Ws.Closed) { e.message ?: "" }
    say("6 a connection without a proper device code is refused, and said so", refused.startsWith("refused:"), refused.take(50))
    a.close(); b.close()
    println(if (bad == 0) "ALL PASS (6)" else "SOME FAILED: $bad"); kotlin.system.exitProcess(if (bad == 0) 0 else 1)
}
