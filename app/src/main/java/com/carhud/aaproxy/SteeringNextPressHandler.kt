package com.carhud.aaproxy

/** One shared state machine for key events and MediaSession transport callbacks.
 * All calls and scheduled work must run on the same thread.
 */
internal class SteeringNextPressHandler(
    private val clock: () -> Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit)
) {
    enum class Source { KEY_EVENT, TRANSPORT }

    private class Pending(val deadline: Long, val action: () -> Unit) {
        var cancelTimer: () -> Unit = {}
    }

    private var pending: Pending? = null
    private var lastKeyDownTime: Long? = null
    private var lastSource: Source? = null
    private var lastAcceptedAt = 0L
    private var suppressUntil = 0L

    fun press(
        source: Source,
        keyDownTime: Long? = null,
        repeatCount: Int = 0,
        doubleClickVoice: Boolean,
        windowMs: Long,
        singlePressVoice: Boolean,
        onSingle: () -> Unit,
        onVoice: () -> Unit
    ) {
        // A held key or the same DOWN delivered to two native entry points is one press.
        if (repeatCount > 0) return
        if (keyDownTime != null && keyDownTime > 0) {
            if (keyDownTime == lastKeyDownTime) return
            lastKeyDownTime = keyDownTime
        }
        val now = clock()
        if (now < suppressUntil) return
        // Guard against mirrored callbacks for one press. Only collapse
        // near-simultaneous events from different sources.
        if (lastSource != null && lastSource != source && now - lastAcceptedAt in 0L..60L) return
        lastSource = source
        lastAcceptedAt = now
        val window = windowMs.coerceIn(300L, 1000L)

        if (singlePressVoice) {
            cancelPending()
            suppressUntil = now + window
            onVoice()
            return
        }
        if (!doubleClickVoice) {
            cancelPending()
            onSingle()
            return
        }

        pending?.let { first ->
            cancelPending()
            if (now < first.deadline) {
                // Cancel BEFORE opening the mic. No delayed Next may survive this pair.
                suppressUntil = now + window
                onVoice()
                return
            }
            // The UI thread can be busy past the timer deadline. This was a single
            // press, not a double; execute it once and start a new window below.
            first.action()
        }

        val first = Pending(now + window, onSingle)
        pending = first
        first.cancelTimer = schedule(window) {
            if (pending === first) {
                pending = null
                first.action()
            }
        }
    }

    fun cancelPending() {
        val first = pending
        pending = null
        first?.cancelTimer?.invoke()
    }
}
