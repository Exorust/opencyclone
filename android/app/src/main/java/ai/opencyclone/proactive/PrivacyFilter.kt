package ai.opencyclone.proactive

import java.net.URI

object PrivacyFilter {
    private val code = Regex("(?<![A-Za-z0-9])(?:[0-9]{4,8}|(?=[A-Za-z0-9]{6,8}(?![A-Za-z0-9]))(?=[A-Za-z0-9]*[A-Za-z])(?=[A-Za-z0-9]*[0-9])[A-Za-z0-9]{6,8})(?![A-Za-z0-9])")
    private val keyword = Regex("(?i)(?<![a-z])(?:code|otp|passcode|verification|verify|one-time|pin|2fa|security code|código)(?![a-z])|ओटीपी|कोड|验证码")
    private val link = Regex("(?i)https?://[^\\s<>]+")
    private val sensitivePath = Regex("(?i)reset|verify|signin|login|magic")

    fun shouldDrop(incoming: Incoming, blockedPackages: Set<String>): Boolean {
        if (incoming.packageName in blockedPackages) return true
        val text = incoming.title + " " + incoming.text
        if (link.findAll(text).any {
            runCatching { sensitivePath.containsMatchIn(URI(it.value).path.orEmpty()) }.getOrDefault(false)
        }) return true
        return keyword.findAll(text).any { word ->
            code.findAll(text).any { value ->
                val start = minOf(word.range.last + 1, value.range.last + 1)
                val end = maxOf(word.range.first, value.range.first)
                val between = if (end > start) text.substring(start, end) else ""
                Regex("[\\p{L}\\p{M}\\p{N}]+").findAll(between).count() <= 6
            }
        }
    }
}
