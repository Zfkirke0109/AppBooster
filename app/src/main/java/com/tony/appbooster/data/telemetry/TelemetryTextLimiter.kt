package com.tony.appbooster.data.telemetry

internal object TelemetryTextLimiter {
    const val DEFAULT_MAX_BYTES = 8 * 1024

    fun limit(
        value: String?,
        maxBytes: Int = DEFAULT_MAX_BYTES
    ): BoundedText {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        if (value == null) return BoundedText(null, truncated = false)
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) {
            return BoundedText(value, truncated = false)
        }

        var charIndex = 0
        var byteCount = 0
        while (charIndex < value.length) {
            val codePoint = value.codePointAt(charIndex)
            val codePointBytes = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (byteCount + codePointBytes > maxBytes) break
            byteCount += codePointBytes
            charIndex += Character.charCount(codePoint)
        }
        return BoundedText(value.substring(0, charIndex), truncated = true)
    }

    data class BoundedText(val value: String?, val truncated: Boolean)
}
