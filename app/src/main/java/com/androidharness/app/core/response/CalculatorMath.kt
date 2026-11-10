package com.androidharness.app.core.response

import kotlin.math.pow

/** Bounded arithmetic only: no scripts, reflection, URLs, or executable agent actions. */
private typealias Arithmetic = (Map<String, Double>) -> Double

object CalculatorMath {
    fun format(value: Double): String = java.math.BigDecimal.valueOf(value)
        .round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()

    fun isValid(expression: String, variables: Set<String>): Boolean = runCatching { Parser(expression, variables).parse(); true }.getOrDefault(false)
    fun evaluate(expression: String, values: Map<String, Double>): Double? = runCatching {
        Parser(expression, values.keys).parse().invoke(values).takeIf { it.isFinite() && kotlin.math.abs(it) <= 1e15 }
    }.getOrNull()

    private class Parser(private val source: String, private val variables: Set<String>) {
        private var pos = 0
        private var depth = 0
        private var nodes = 0
        fun parse(): Arithmetic {
            require(source.length in 1..300)
            val result = expression()
            space(); require(pos == source.length)
            return result
        }
        private fun space() { while (pos < source.length && source[pos].isWhitespace()) pos++ }
        private fun take(char: Char): Boolean { space(); return if (source.getOrNull(pos) == char) { pos++; true } else false }
        private fun expression(): Arithmetic {
            var left = term()
            while (true) {
                val add = take('+'); if (!add && !take('-')) return left
                require(++nodes <= 128)
                val a = left; val b = term()
                left = if (add) { v -> a(v) + b(v) } else { v -> a(v) - b(v) }
            }
        }
        private fun term(): Arithmetic {
            var left = factor()
            while (true) {
                val multiply = take('*'); if (!multiply && !take('/')) return left
                require(++nodes <= 128)
                val a = left; val b = factor()
                left = if (multiply) { v -> a(v) * b(v) } else { v -> a(v) / b(v) }
            }
        }
        private fun factor(): Arithmetic {
            require(++depth <= 16 && ++nodes <= 128)
            try {
                if (take('+')) return factor()
                if (take('-')) { val child = factor(); return { -child(it) } }
                val base: Arithmetic = if (take('(')) {
                    expression().also { require(take(')')) }
                } else {
                    space()
                    val start = pos
                    if (source.getOrNull(pos)?.let { it.isLetter() } == true) {
                        while (source.getOrNull(pos)?.let { it.isLetterOrDigit() || it == '_' } == true) pos++
                        val name = source.substring(start, pos)
                        if (take('(')) {
                            val a = expression()
                            val b = if (take(',')) expression() else null
                            require(take(')'))
                            when (name) {
                                "min" -> { require(b != null); { v -> minOf(a(v), b(v)) } }
                                "max" -> { require(b != null); { v -> maxOf(a(v), b(v)) } }
                                "abs" -> { require(b == null); { v -> kotlin.math.abs(a(v)) } }
                                "sqrt" -> { require(b == null); { v -> kotlin.math.sqrt(a(v)) } }
                                "round" -> { require(b == null); { v -> kotlin.math.round(a(v)) } }
                                else -> error("Unsupported function")
                            }
                        } else { require(name in variables); { v -> requireNotNull(v[name]) } }
                    } else {
                        while (source.getOrNull(pos)?.let { it.isDigit() || it == '.' } == true) pos++
                        val number = source.substring(start, pos).toDouble().also { require(it.isFinite()) };
                        { _: Map<String, Double> -> number }
                    }
                }
                if (take('^')) { val exponent = factor(); return { base(it).pow(exponent(it)) } }
                return base
            } finally { depth-- }
        }
    }
}
