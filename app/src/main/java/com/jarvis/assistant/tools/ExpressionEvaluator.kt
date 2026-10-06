package com.jarvis.assistant.tools

import kotlin.math.PI
import kotlin.math.E
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class ExpressionException(message: String) : Exception(message)

/**
 * Safe arithmetic evaluator (recursive descent). Supports + - * / ^ % ( ), unary minus, constants
 * pi / e, and sqrt sin cos tan ln log abs floor. No scripting engine, so nothing else can run.
 */
object ExpressionEvaluator {
    fun evaluate(input: String): Double {
        val p = Parser(normalize(input))
        val value = p.parseExpression()
        p.skipSpaces()
        if (!p.atEnd()) throw ExpressionException("Unexpected '${p.peek()}'")
        if (value.isNaN() || value.isInfinite()) throw ExpressionException("Not a finite number")
        return value
    }

    /** Spoken Korean / English operator words -> symbols. */
    fun normalize(raw: String): String {
        var s = raw.lowercase().replace(",", "")
        val words = listOf(
            "더하기" to "+", "플러스" to "+", "plus" to "+", "빼기" to "-", "마이너스" to "-", "minus" to "-",
            "곱하기" to "*", "times" to "*", "multiplied by" to "*", "x" to "*", "×" to "*",
            "나누기" to "/", "divided by" to "/", "÷" to "/", "제곱근" to "sqrt", "루트" to "sqrt",
            "퍼센트" to "%", "percent" to "%",
        )
        for ((w, sym) in words) {
            s = if (w == "x") Regex("(?<=[\\d)])\\s*x\\s*(?=[\\d(])").replace(s, sym) else s.replace(w, sym)
        }
        return s.trim()
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun atEnd() = pos >= s.length
        fun peek(): Char = if (atEnd()) '\u0000' else s[pos]
        fun skipSpaces() {
            while (!atEnd() && s[pos].isWhitespace()) pos++
        }

        fun parseExpression(): Double {
            var v = parseTerm()
            while (true) {
                skipSpaces()
                v = when (peek()) {
                    '+' -> { pos++; v + parseTerm() }
                    '-' -> { pos++; v - parseTerm() }
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parsePower()
            while (true) {
                skipSpaces()
                v = when (peek()) {
                    '*' -> { pos++; v * parsePower() }
                    '/' -> {
                        pos++
                        val d = parsePower()
                        if (d == 0.0) throw ExpressionException("Division by zero")
                        v / d
                    }
                    '%' -> {
                        pos++
                        // "20% of 50" style is handled upstream; here % is modulo between two operands.
                        skipSpaces()
                        if (atEnd() || peek() == ')') v / 100.0 else v % parsePower()
                    }
                    else -> return v
                }
            }
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            skipSpaces()
            if (peek() == '^') {
                pos++
                return base.pow(parsePower())
            }
            return base
        }

        private fun parseUnary(): Double {
            skipSpaces()
            return when (peek()) {
                '-' -> { pos++; -parseUnary() }
                '+' -> { pos++; parseUnary() }
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            skipSpaces()
            val c = peek()
            if (c == '(') {
                pos++
                val v = parseExpression()
                skipSpaces()
                if (peek() != ')') throw ExpressionException("Missing )")
                pos++
                return v
            }
            if (c.isDigit() || c == '.') {
                val start = pos
                while (!atEnd() && (s[pos].isDigit() || s[pos] == '.')) pos++
                return s.substring(start, pos).toDoubleOrNull() ?: throw ExpressionException("Bad number")
            }
            if (c.isLetter()) {
                val start = pos
                while (!atEnd() && s[pos].isLetter()) pos++
                val name = s.substring(start, pos)
                when (name) {
                    "pi" -> return PI
                    "e" -> return E
                }
                skipSpaces()
                if (peek() != '(') {
                    // allow "sqrt 16"
                    val arg = parseUnary()
                    return apply(name, arg)
                }
                pos++
                val arg = parseExpression()
                skipSpaces()
                if (peek() != ')') throw ExpressionException("Missing )")
                pos++
                return apply(name, arg)
            }
            throw ExpressionException("Unexpected '${if (atEnd()) "end" else c}'")
        }

        private fun apply(name: String, x: Double): Double = when (name) {
            "sqrt" -> if (x < 0) throw ExpressionException("sqrt of negative") else sqrt(x)
            "sin" -> sin(Math.toRadians(x))
            "cos" -> cos(Math.toRadians(x))
            "tan" -> tan(Math.toRadians(x))
            "ln" -> ln(x)
            "log" -> log10(x)
            "abs" -> abs(x)
            "floor" -> floor(x)
            else -> throw ExpressionException("Unknown function $name")
        }
    }

    /** 1234.5 -> "1234.5", 3.0 -> "3", 0.1+0.2 -> "0.3" (max 6 decimals). */
    fun format(v: Double): String {
        val rounded = Math.round(v * 1_000_000.0) / 1_000_000.0
        return if (rounded == floor(rounded) && abs(rounded) < 1e15) rounded.toLong().toString()
        else rounded.toString().trimEnd('0').trimEnd('.')
    }
}
