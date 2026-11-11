package com.share64.app.core

/**
 * Tiny strict JSON parser (objects -> Map, arrays -> List, numbers -> Long/Double). Pure Kotlin so
 * release parsing is unit-testable on the JVM. Throws IllegalArgumentException on malformed input.
 */
object MiniJson {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value(0)
        p.skipWs()
        require(p.atEnd()) { "trailing data at ${p.pos}" }
        return v
    }

    private class Parser(private val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        private fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg at $pos")

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            skipWs()
            if (atEnd()) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("bad literal")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') fail("expected key")
                val k = str()
                skipWs()
                if (atEnd() || s[pos] != ':') fail("expected ':'")
                pos++
                m[k] = value(depth + 1)
                skipWs()
                if (atEnd()) fail("unterminated object")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            val l = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                l += value(depth + 1)
                skipWs()
                if (atEnd()) fail("unterminated array")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("bad escape")
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad \\u escape")
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar() ?: fail("bad \\u escape"))
                                pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            while (pos < s.length && s[pos] in "+-0123456789.eE") pos++
            val t = s.substring(start, pos)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("bad number '$t'")
        }
    }
}
