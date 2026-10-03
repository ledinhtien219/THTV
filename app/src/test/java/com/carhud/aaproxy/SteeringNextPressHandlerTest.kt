package com.carhud.aaproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class SteeringNextPressHandlerTest {
    private class Fixture {
        class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 10_000L
        val tasks = mutableListOf<Task>()
        val effects = mutableListOf<String>()
        val handler = SteeringNextPressHandler(
            clock = { now },
            schedule = { delay, action ->
                val task = Task(now + delay, action)
                tasks.add(task)
                val cancel: () -> Unit = { task.cancelled = true }
                cancel
            }
        )

        fun press(
            source: SteeringNextPressHandler.Source = SteeringNextPressHandler.Source.KEY_EVENT,
            downTime: Long? = if (source == SteeringNextPressHandler.Source.KEY_EVENT) now else null,
            repeat: Int = 0,
            doubleVoice: Boolean = true,
            window: Long = 500,
            singleVoice: Boolean = false
        ) = handler.press(source, downTime, repeat, doubleVoice, window, singleVoice,
            onSingle = { effects.add("next") }, onVoice = { effects.add("voice") })

        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                tasks.remove(task)
                now = maxOf(now, task.at)
                task.action()
            }
            now = target
        }
    }

    @Test fun onePressWaitsThenSkipsExactlyOnce() {
        val f = Fixture()
        f.press()
        f.advance(499)
        assertEquals(emptyList<String>(), f.effects)
        f.advance(1)
        assertEquals(listOf("next"), f.effects)
        f.advance(2000)
        assertEquals(listOf("next"), f.effects)
    }

    @Test fun twoKeyPressesOnlyOpenVoiceEvenAfterOriginalTimerDeadline() {
        val f = Fixture()
        f.press()
        val staleTask = f.tasks.single()
        f.advance(200)
        f.press()
        assertEquals(listOf("voice"), f.effects)
        staleTask.action() // A queued callback must still be harmless after cancellation.
        f.advance(2000)
        assertEquals(listOf("voice"), f.effects)
    }

    @Test fun mediaSessionTransportDoublePressAlsoOnlyOpensVoice() {
        val f = Fixture()
        f.press(SteeringNextPressHandler.Source.TRANSPORT)
        f.advance(200)
        f.press(SteeringNextPressHandler.Source.TRANSPORT)
        f.advance(2000)
        assertEquals(listOf("voice"), f.effects)
    }

    @Test fun mixedNativeAndTransportPressesShareTheSameWindow() {
        val f = Fixture()
        f.press()
        f.advance(200)
        f.press(SteeringNextPressHandler.Source.TRANSPORT)
        f.advance(2000)
        assertEquals(listOf("voice"), f.effects)
    }

    @Test fun holdingKeyDoesNotOpenMicOrSkipTwice() {
        val f = Fixture()
        val downTime = f.now
        f.press(downTime = downTime)
        f.advance(100)
        f.press(downTime = downTime, repeat = 1)
        f.advance(100)
        f.press(downTime = downTime, repeat = 2)
        f.advance(1500)
        assertEquals(listOf("next"), f.effects)
    }

    @Test fun sameDownDeliveredToBothNativeEntryPointsIsStillOnePress() {
        val f = Fixture()
        val downTime = f.now
        f.press(downTime = downTime)
        f.advance(100)
        f.press(downTime = downTime)
        f.advance(1000)
        assertEquals(listOf("next"), f.effects)
    }

    @Test fun transportMirrorDoesNotCreateFalseDoublePress() {
        val f = Fixture()
        f.press()
        f.advance(5)
        f.press(SteeringNextPressHandler.Source.TRANSPORT)
        f.advance(1000)
        assertEquals(listOf("next"), f.effects)
    }

    @Test fun missingKeyTimestampsDoNotSuppressAllFuturePresses() {
        val f = Fixture()
        f.press(downTime = 0L)
        f.advance(200)
        f.press(downTime = 0L)
        f.advance(2000)
        assertEquals(listOf("voice"), f.effects)
    }

    @Test fun twoPhysicalPressesWithMirrorsOpenMicOnlyOnce() {
        val f = Fixture()
        f.press()
        f.advance(5)
        f.press(SteeringNextPressHandler.Source.TRANSPORT)
        f.advance(195)
        f.press()
        f.advance(5)
        f.press(SteeringNextPressHandler.Source.TRANSPORT)
        f.advance(1000)
        assertEquals(listOf("voice"), f.effects)
    }

    @Test fun rapidThirdPressCannotSkipWhileMicStarts() {
        val f = Fixture()
        f.press()
        f.advance(200)
        f.press()
        f.advance(100)
        f.press()
        f.advance(2000)
        assertEquals(listOf("voice"), f.effects)
        f.press()
        f.advance(500)
        assertEquals(listOf("voice", "next"), f.effects)
    }

    @Test fun configuredDetectionWindowsAreHonored() {
        for (window in listOf(400L, 500L, 700L)) {
            val f = Fixture()
            f.press(window = window)
            f.advance(window - 1)
            f.press(window = window)
            f.advance(2000)
            assertEquals("window=$window", listOf("voice"), f.effects)
        }
    }

    @Test fun atTheDeadlineTwoPressesAreSeparateSingles() {
        val f = Fixture()
        f.press()
        f.advance(500)
        f.press()
        f.advance(500)
        assertEquals(listOf("next", "next"), f.effects)
    }

    @Test fun disabledDoubleVoiceSkipsImmediatelyOnEachPress() {
        val f = Fixture()
        f.press(doubleVoice = false)
        assertEquals(listOf("next"), f.effects)
        f.advance(200)
        f.press(doubleVoice = false)
        f.advance(1000)
        assertEquals(listOf("next", "next"), f.effects)
    }

    @Test fun voiceMappedSingleDoesNotRestartMicOnSecondPress() {
        val f = Fixture()
        f.press(singleVoice = true)
        assertEquals(listOf("voice"), f.effects)
        f.advance(200)
        f.press(singleVoice = true)
        f.advance(2000)
        assertEquals(listOf("voice"), f.effects)
    }

    @Test fun switchingAppOrOpeningMicCancelsPendingSkip() {
        val f = Fixture()
        f.press()
        f.advance(100)
        f.handler.cancelPending()
        f.advance(2000)
        assertEquals(emptyList<String>(), f.effects)
    }

    @Test fun busyUiThreadCannotTurnLateSingleIntoDoubleOrRunStaleTimer() {
        val f = Fixture()
        f.press()
        val oldTask = f.tasks.single()
        f.now += 700 // Elapsed time without allowing the scheduler to run.
        f.press()
        assertEquals(listOf("next"), f.effects)
        oldTask.action()
        f.advance(200)
        f.press()
        f.advance(2000)
        assertEquals(listOf("next", "voice"), f.effects)
    }
}
