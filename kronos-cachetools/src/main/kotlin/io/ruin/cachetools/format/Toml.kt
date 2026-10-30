package io.ruin.cachetools.format

class TomlException(message: String) : Exception(message)

/**
 * The subset of TOML that definition files need: top-level `key = value` pairs whose values are integers, strings,
 * booleans or (nested) arrays of those. Tables, floats and dates are refused with a line number rather than ignored,
 * so a typo can never silently drop a value.
 */
object Toml {

    fun parse(text: String): LinkedHashMap<String, Any> = Parser(text).document()

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    fun value(v: Any): String = when (v) {
        is String -> quote(v)
        is List<*> -> v.joinToString(", ", "[", "]") { value(it!!) }
        else -> v.toString()
    }

    private class Parser(private val s: String) {
        private var p = 0
        private var line = 1

        fun document(): LinkedHashMap<String, Any> {
            val result = LinkedHashMap<String, Any>()
            while (true) {
                skipBlank(newlines = true)
                if (p >= s.length) return result
                if (s[p] == '[') fail("tables ([...]) are not supported; put every key at the top level")
                val key = key()
                skipBlank(newlines = false)
                expect('=')
                skipBlank(newlines = false)
                val value = value()
                if (result.put(key, value) != null) fail("'$key' is set twice")
                skipBlank(newlines = false)
                if (p < s.length && s[p] != '\n') fail("expected a new line after the value of '$key'")
            }
        }

        private fun key(): String {
            if (s[p] == '"') return basicString()
            val start = p
            while (p < s.length && (s[p].isLetterOrDigit() || s[p] == '_' || s[p] == '-')) p++
            if (p == start) fail("expected a key")
            if (p < s.length && s[p] == '.') fail("dotted keys are not supported")
            return s.substring(start, p)
        }

        private fun value(): Any {
            if (p >= s.length) fail("expected a value")
            return when (val c = s[p]) {
                '"' -> { if (s.startsWith("\"\"\"", p)) fail("multi-line strings are not supported"); basicString() }
                '\'' -> literalString()
                '[' -> array()
                '{' -> fail("inline tables are not supported")
                else -> when {
                    s.startsWith("true", p) -> { p += 4; true }
                    s.startsWith("false", p) -> { p += 5; false }
                    c == '-' || c == '+' || c.isDigit() -> integer()
                    else -> fail("unexpected '$c'")
                }
            }
        }

        private fun array(): List<Any> {
            expect('[')
            val items = ArrayList<Any>()
            while (true) {
                skipBlank(newlines = true)
                if (p < s.length && s[p] == ']') { p++; return items }
                items += value()
                skipBlank(newlines = true)
                when {
                    p < s.length && s[p] == ',' -> p++
                    p < s.length && s[p] == ']' -> { p++; return items }
                    else -> fail("expected ',' or ']' in array")
                }
            }
        }

        private fun integer(): Int {
            val start = p
            val negative = s[p] == '-'
            if (s[p] == '-' || s[p] == '+') p++
            val hex = s.startsWith("0x", p)
            if (hex) p += 2
            val digitsStart = p
            while (p < s.length && (s[p].isLetterOrDigit() || s[p] == '_')) p++
            if (p < s.length && s[p] == '.') fail("only integers are supported")
            val magnitude = s.substring(digitsStart, p).replace("_", "").toLongOrNull(if (hex) 16 else 10)
            val value = magnitude?.let { if (negative) -it else it }
            if (value == null || value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) fail("'${s.substring(start, p)}' is not a valid integer")
            return value.toInt()
        }

        private fun basicString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (p >= s.length || s[p] == '\n') fail("unterminated string")
                val c = s[p++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                if (p >= s.length) fail("unterminated string")
                when (val e = s[p++]) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); 'n' -> sb.append('\n'); 't' -> sb.append('\t')
                    'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                    'u', 'U' -> {
                        val len = if (e == 'u') 4 else 8
                        val code = s.substring(p, minOf(p + len, s.length)).toIntOrNull(16) ?: fail("bad \\$e escape")
                        sb.appendCodePoint(code); p += len
                    }
                    else -> fail("unknown escape \\$e")
                }
            }
        }

        private fun literalString(): String {
            expect('\'')
            val start = p
            while (p < s.length && s[p] != '\'' && s[p] != '\n') p++
            if (p >= s.length || s[p] != '\'') fail("unterminated string")
            return s.substring(start, p++)
        }

        private fun skipBlank(newlines: Boolean) {
            while (p < s.length) {
                when (s[p]) {
                    ' ', '\t', '\r' -> p++
                    '\n' -> if (newlines) { p++; line++ } else return
                    '#' -> while (p < s.length && s[p] != '\n') p++
                    else -> return
                }
            }
        }

        private fun expect(c: Char) {
            if (p >= s.length || s[p] != c) fail("expected '$c'")
            p++
        }

        private fun fail(message: String): Nothing = throw TomlException("line $line: $message")
    }
}
