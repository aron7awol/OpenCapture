package org.json

class JSONException(message: String) : RuntimeException(message)

class JSONObject() {
    companion object {
        @JvmField
        val NULL: Any = NullValue
    }

    private val values = LinkedHashMap<String, Any>()

    constructor(json: String) : this() {
        val parser = Parser(json)
        val parsed = parser.parseValue()
        require(parsed is JSONObject) { "JSON root is not an object" }
        values.putAll(parsed.values)
        parser.requireEnd()
    }

    fun put(name: String, value: Any?): JSONObject = putAny(name, value)

    fun put(name: String, value: Int): JSONObject = putAny(name, value)

    fun put(name: String, value: Long): JSONObject = putAny(name, value)

    fun put(name: String, value: Double): JSONObject = putAny(name, value)

    fun put(name: String, value: Float): JSONObject = putAny(name, value)

    fun put(name: String, value: Boolean): JSONObject = putAny(name, value)

    fun put(name: String, value: String): JSONObject = putAny(name, value)

    private fun putAny(name: String, value: Any?): JSONObject {
        if (value == null) values.remove(name) else values[name] = value
        return this
    }

    fun has(name: String): Boolean = values.containsKey(name)

    fun isNull(name: String): Boolean = !values.containsKey(name) || values[name] === NULL

    fun get(name: String): Any = values[name] ?: throw JSONException("missing key $name")

    fun opt(name: String): Any? = values[name]

    fun getString(name: String): String = get(name).let {
        if (it !is String) throw JSONException("$name is not a string") else it
    }

    fun optString(name: String, fallback: String = ""): String =
        (values[name] as? String) ?: fallback

    fun getJSONObject(name: String): JSONObject = get(name).let {
        if (it !is JSONObject) throw JSONException("$name is not an object") else it
    }

    fun optJSONObject(name: String): JSONObject? = values[name] as? JSONObject

    fun getInt(name: String): Int = getNumber(name).toInt()

    fun getDouble(name: String): Double = getNumber(name).toDouble()

    fun optInt(name: String, fallback: Int = 0): Int = (values[name] as? Number)?.toInt() ?: fallback

    fun optLong(name: String, fallback: Long = 0L): Long = (values[name] as? Number)?.toLong() ?: fallback

    fun optDouble(name: String, fallback: Double = Double.NaN): Double =
        (values[name] as? Number)?.toDouble() ?: fallback

    fun optBoolean(name: String, fallback: Boolean = false): Boolean =
        (values[name] as? Boolean) ?: fallback

    fun keys(): kotlin.collections.Iterator<String> = values.keys.iterator()

    override fun toString(): String = values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "${quote(key)}:${stringify(value)}"
    }

    private fun getNumber(name: String): Number = get(name).let {
        if (it !is Number) throw JSONException("$name is not a number") else it
    }

    private class Parser(private val input: String) {
        private var index = 0

        fun parseValue(): Any {
            skipWhitespace()
            if (index >= input.length) throw JSONException("unexpected end of JSON")
            return when (input[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", NULL)
                else -> parseNumber()
            }
        }

        fun requireEnd() {
            skipWhitespace()
            if (index != input.length) throw JSONException("trailing JSON data")
        }

        private fun parseObject(): JSONObject {
            expect('{')
            val objectValue = JSONObject()
            skipWhitespace()
            if (consume('}')) return objectValue
            while (true) {
                skipWhitespace()
                if (index >= input.length || input[index] != '"') throw JSONException("object key is not a string")
                val key = parseString()
                skipWhitespace()
                expect(':')
                objectValue.put(key, parseValue())
                skipWhitespace()
                if (consume('}')) return objectValue
                expect(',')
            }
        }

        private fun parseArray(): JSONArray {
            expect('[')
            val array = JSONArray()
            skipWhitespace()
            if (consume(']')) return array
            while (true) {
                array.put(parseValue())
                skipWhitespace()
                if (consume(']')) return array
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val output = StringBuilder()
            while (index < input.length) {
                when (val ch = input[index++]) {
                    '"' -> return output.toString()
                    '\\' -> {
                        if (index >= input.length) throw JSONException("unterminated escape")
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> output.append(escaped)
                            'b' -> output.append('\b')
                            'f' -> output.append('\u000c')
                            'n' -> output.append('\n')
                            'r' -> output.append('\r')
                            't' -> output.append('\t')
                            'u' -> {
                                if (index + 4 > input.length) throw JSONException("short unicode escape")
                                output.append(input.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> throw JSONException("invalid escape")
                        }
                    }
                    else -> output.append(ch)
                }
            }
            throw JSONException("unterminated string")
        }

        private fun parseNumber(): Number {
            val start = index
            if (index < input.length && input[index] == '-') index++
            while (index < input.length && input[index].isDigit()) index++
            var floating = false
            if (index < input.length && input[index] == '.') {
                floating = true
                index++
                while (index < input.length && input[index].isDigit()) index++
            }
            if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
                floating = true
                index++
                if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
                while (index < input.length && input[index].isDigit()) index++
            }
            val token = input.substring(start, index)
            return try {
                if (floating) token.toDouble() else token.toLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
            } catch (_: NumberFormatException) {
                throw JSONException("invalid number")
            }
        }

        private fun <T> parseLiteral(literal: String, value: T): T {
            if (!input.startsWith(literal, index)) throw JSONException("invalid literal")
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < input.length && input[index].isWhitespace()) index++
        }

        private fun expect(expected: Char) {
            if (index >= input.length || input[index++] != expected) throw JSONException("expected $expected")
        }

        private fun consume(expected: Char): Boolean {
            if (index < input.length && input[index] == expected) {
                index++
                return true
            }
            return false
        }
    }

    private object NullValue
}

class JSONArray() {
    private val values = mutableListOf<Any>()

    constructor(values: Collection<*>) : this() {
        values.forEach { put(it) }
    }

    fun put(value: Any?): JSONArray = putAny(value)

    fun put(value: Int): JSONArray = putAny(value)

    fun put(value: Long): JSONArray = putAny(value)

    fun put(value: Double): JSONArray = putAny(value)

    fun put(value: Float): JSONArray = putAny(value)

    fun put(value: Boolean): JSONArray = putAny(value)

    private fun putAny(value: Any?): JSONArray {
        values += value ?: JSONObject.NULL
        return this
    }

    fun length(): Int = values.size

    override fun toString(): String = values.joinToString(prefix = "[", postfix = "]") { stringify(it) }
}

private fun stringify(value: Any): String = when (value) {
    JSONObject.NULL -> "null"
    is String -> quote(value)
    is JSONObject, is JSONArray -> value.toString()
    else -> value.toString()
}

private fun quote(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
    append('"')
}
