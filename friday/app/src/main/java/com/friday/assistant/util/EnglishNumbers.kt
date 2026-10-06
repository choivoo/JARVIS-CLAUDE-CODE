package com.friday.assistant.util

/** Spells small integers so TTS reads them naturally ("seventy-two"). */
object EnglishNumbers {
    private val ones = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    fun words(n: Int): String = when {
        n < 0 -> "minus " + words(-n)
        n < 20 -> ones[n]
        n < 100 -> tens[n / 10] + if (n % 10 == 0) "" else "-" + ones[n % 10]
        n < 1000 -> ones[n / 100] + " hundred" + if (n % 100 == 0) "" else " and " + words(n % 100)
        else -> n.toString()
    }
}
