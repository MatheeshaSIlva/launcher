package dev.launcher.engine.shell

/** In-memory field input only. Never trim, coerce Unicode digits, log, or persist the code. */
object PairingCode {
    const val MAX_INPUT_CHARS = 16

    fun parse(input: CharSequence?): String? {
        if (input == null || input.length > MAX_INPUT_CHARS || input.length != 6) return null
        val digits = CharArray(6)
        for (i in digits.indices) {
            val digit = input[i]
            if (digit !in '0'..'9') return null
            digits[i] = digit
        }
        return digits.concatToString()
    }
}
