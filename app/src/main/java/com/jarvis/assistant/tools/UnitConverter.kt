package com.jarvis.assistant.tools

object UnitConverter {
    private enum class Dim { LENGTH, MASS, VOLUME, SPEED, DATA, TIME, AREA, TEMP }
    private class U(val dim: Dim, val toBase: Double, val name: String)

    // base units: m, kg, l, m/s, byte, s, m2
    private val units: Map<String, U> = buildMap {
        fun add(u: U, vararg names: String) = names.forEach { put(it.lowercase(), u) }
        add(U(Dim.LENGTH, 1.0, "meter"), "m", "meter", "meters", "미터")
        add(U(Dim.LENGTH, 1000.0, "kilometer"), "km", "kilometer", "kilometers", "킬로미터", "키로")
        add(U(Dim.LENGTH, 0.01, "centimeter"), "cm", "centimeter", "centimeters", "센티미터", "센치")
        add(U(Dim.LENGTH, 0.001, "millimeter"), "mm", "millimeter", "millimeters", "밀리미터")
        add(U(Dim.LENGTH, 1609.344, "mile"), "mi", "mile", "miles", "마일")
        add(U(Dim.LENGTH, 0.9144, "yard"), "yd", "yard", "yards", "야드")
        add(U(Dim.LENGTH, 0.3048, "foot"), "ft", "foot", "feet", "피트")
        add(U(Dim.LENGTH, 0.0254, "inch"), "in", "inch", "inches", "인치")
        add(U(Dim.MASS, 1.0, "kilogram"), "kg", "kilogram", "kilograms", "킬로그램", "킬로")
        add(U(Dim.MASS, 0.001, "gram"), "g", "gram", "grams", "그램")
        add(U(Dim.MASS, 0.45359237, "pound"), "lb", "lbs", "pound", "pounds", "파운드")
        add(U(Dim.MASS, 0.0283495, "ounce"), "oz", "ounce", "ounces", "온스")
        add(U(Dim.VOLUME, 1.0, "liter"), "l", "liter", "liters", "litre", "리터")
        add(U(Dim.VOLUME, 0.001, "milliliter"), "ml", "milliliter", "milliliters", "밀리리터")
        add(U(Dim.VOLUME, 3.785411784, "gallon"), "gal", "gallon", "gallons", "갤런")
        add(U(Dim.VOLUME, 0.2365882365, "cup"), "cup", "cups", "컵")
        add(U(Dim.SPEED, 1.0, "meter per second"), "m/s", "mps")
        add(U(Dim.SPEED, 0.2777777778, "kilometer per hour"), "km/h", "kph", "kmh", "시속")
        add(U(Dim.SPEED, 0.44704, "mile per hour"), "mph")
        add(U(Dim.DATA, 1.0, "byte"), "b", "byte", "bytes", "바이트")
        add(U(Dim.DATA, 1_000.0, "kilobyte"), "kb", "kilobyte", "킬로바이트")
        add(U(Dim.DATA, 1_000_000.0, "megabyte"), "mb", "megabyte", "메가바이트", "메가")
        add(U(Dim.DATA, 1_000_000_000.0, "gigabyte"), "gb", "gigabyte", "기가바이트", "기가")
        add(U(Dim.DATA, 1_000_000_000_000.0, "terabyte"), "tb", "terabyte", "테라바이트")
        add(U(Dim.TIME, 1.0, "second"), "s", "sec", "second", "seconds", "초")
        add(U(Dim.TIME, 60.0, "minute"), "min", "minute", "minutes", "분")
        add(U(Dim.TIME, 3600.0, "hour"), "h", "hr", "hour", "hours", "시간")
        add(U(Dim.TIME, 86400.0, "day"), "day", "days", "일")
        add(U(Dim.AREA, 1.0, "square meter"), "m2", "sqm", "제곱미터")
        add(U(Dim.AREA, 3.305785, "pyeong"), "평", "pyeong")
        add(U(Dim.TEMP, 1.0, "celsius"), "c", "°c", "celsius", "섭씨")
        add(U(Dim.TEMP, 1.0, "fahrenheit"), "f", "°f", "fahrenheit", "화씨")
        add(U(Dim.TEMP, 1.0, "kelvin"), "k", "kelvin", "켈빈")
    }

    class Result(val value: Double, val fromName: String, val toName: String)

    fun convert(value: Double, from: String, to: String): Result? {
        val a = units[from.trim().lowercase()] ?: return null
        val b = units[to.trim().lowercase()] ?: return null
        if (a.dim != b.dim) return null
        if (a.dim == Dim.TEMP) return Result(temp(value, a.name, b.name), a.name, b.name)
        return Result(value * a.toBase / b.toBase, a.name, b.name)
    }

    private fun temp(v: Double, from: String, to: String): Double {
        val c = when (from) {
            "celsius" -> v
            "fahrenheit" -> (v - 32) * 5 / 9
            else -> v - 273.15
        }
        return when (to) {
            "celsius" -> c
            "fahrenheit" -> c * 9 / 5 + 32
            else -> c + 273.15
        }
    }
}
