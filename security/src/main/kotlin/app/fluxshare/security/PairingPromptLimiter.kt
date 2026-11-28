package app.fluxshare.security

/**
 * Limits how many unknown-peer pairing prompts a device shows per minute.
 *
 * Without a limit, any device on the LAN could flood the user with prompts and
 * train them to tap "accept". The limiter is deliberately independent of the
 * network: a rejected prompt is a security failure, never a queued prompt.
 */
class PairingPromptLimiter(
    private val maxPromptsPerMinute: Int = DEFAULT_MAX_PROMPTS_PER_MINUTE,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxPromptsPerMinute > 0) {
            "maxPromptsPerMinute must be positive, was $maxPromptsPerMinute"
        }
    }

    private val attempts = ArrayDeque<Long>()

    /** @return true when a prompt may be shown, false when the limit is reached. */
    fun tryAcquire(): Boolean = synchronized(attempts) {
        val now = clockMillis()
        while (attempts.isNotEmpty() && now - attempts.first() >= WINDOW_MILLIS) {
            attempts.removeFirst()
        }
        if (attempts.size >= maxPromptsPerMinute) {
            false
        } else {
            attempts.addLast(now)
            true
        }
    }

    companion object {
        const val DEFAULT_MAX_PROMPTS_PER_MINUTE: Int = 3

        const val WINDOW_MILLIS: Long = 60_000L
    }
}
