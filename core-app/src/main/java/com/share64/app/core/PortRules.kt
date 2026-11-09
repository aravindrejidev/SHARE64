package com.share64.app.core

/** Validation for the receiver port a person may configure. Pure Kotlin. */
object PortRules {
    const val DEFAULT = 52364 // keep equal to TransferEngine.DEFAULT_PORT
    const val MIN = 1024
    const val MAX = 65535

    /** The port if [text] is a valid choice, else null. */
    fun parse(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in MIN..MAX }

    /** A human-readable problem with [text], or null when it is valid. */
    fun error(text: String): String? {
        val t = text.trim()
        return when {
            t.isEmpty() -> "Enter a port number"
            !t.all { it.isDigit() } -> "Digits only"
            t.length > 5 || t.toInt() !in MIN..MAX -> "Use a number from $MIN to $MAX"
            else -> null
        }
    }
}
