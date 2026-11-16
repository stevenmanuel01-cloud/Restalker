package com.restalker.mobile.core

/** A small JSON reader/writer (no libraries: the same code runs in the app and in the tests on a computer).
 *  Objects -> Map<String, Any?> (insertion order kept), arrays -> List<Any?>, numbers -> Long or Double. */
object MiniJson {
    fun write(v: Any?): String = StringBuilder().also { put(it, v) }.toString()

    private fun put(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> str(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else if (v == Math.floor(v) && Math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v.toString())
            is Float -> put(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{'); var first = true
                for ((k, x) in v) { if (!first) sb.append(','); first = false; str(sb, k.toString()); sb.append(':'); put(sb, x) }
                sb.append('}')
            }
            is Iterable<*> -> { sb.append('['); var first = true; for (x in v) { if (!first) sb.append(','); first = false; put(sb, x) }; sb.append(']') }
            is Array<*> -> put(sb, v.toList())
            else -> str(sb, v.toString())
        }
    }

    private fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            ' ' -> sb.append("\\u2028"); ' ' -> sb.append("\\u2029")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    fun read(s: String): Any? { val p = P(s); p.ws(); val v = p.value(); p.ws(); if (p.i != s.length) throw IllegalArgumentException("extra text at ${p.i}"); return v }

    @Suppress("UNCHECKED_CAST")
    fun obj(s: String?): Map<String, Any?>? = try { if (s.isNullOrBlank()) null else read(s) as? Map<String, Any?> } catch (e: Exception) { null }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws(); if (i >= s.length) throw IllegalArgumentException("end of text")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> string()
                't' -> { word("true"); true }
                'f' -> { word("false"); false }
                'n' -> { word("null"); null }
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("unexpected '$c' at $i")
            }
        }
        fun obj(): Map<String, Any?> {
            i++; val m = LinkedHashMap<String, Any?>(); ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = string(); ws(); expect(':'); m[k] = value(); ws()
                if (s[i] == ',') { i++ } else { expect('}'); return m }
            }
        }
        fun arr(): List<Any?> {
            i++; val a = ArrayList<Any?>(); ws()
            if (s[i] == ']') { i++; return a }
            while (true) {
                a.add(value()); ws()
                if (s[i] == ',') { i++ } else { expect(']'); return a }
            }
        }
        fun expect(c: Char) { if (i >= s.length || s[i] != c) throw IllegalArgumentException("expected '$c' at $i"); i++ }
        fun word(w: String) { if (!s.startsWith(w, i)) throw IllegalArgumentException("expected $w at $i"); i += w.length }
        fun number(): Any {
            val st = i; if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
        fun string(): String {
            expect('"'); val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("unterminated text")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}

fun Any?.asDouble(): Double? = when (this) { is Number -> this.toDouble(); is String -> this.toDoubleOrNull(); else -> null }
fun Any?.asLong(): Long? = when (this) { is Number -> this.toLong(); is String -> this.toLongOrNull(); else -> null }
fun Any?.asBool(): Boolean = this == true
fun Any?.asStr(): String? = this as? String
@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>
fun Any?.asList(): List<Any?> = (this as? List<Any?>) ?: emptyList()
