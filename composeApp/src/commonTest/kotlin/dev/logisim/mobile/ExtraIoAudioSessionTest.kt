package dev.logisim.mobile

import dev.logisim.mobile.core.BuzzerPcm
import dev.logisim.mobile.core.BuzzerTone
import dev.logisim.mobile.core.localization.AppLanguage
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.LocalizedStateException
import dev.logisim.mobile.core.localization.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtraIoAudioSessionTest {
    private class Player(private val fail: Boolean = false) : ExtraIoAudioPlayer {
        var plays = 0
        var stops = 0
        var pcm: BuzzerPcm? = null
        override fun play(pcm: BuzzerPcm) {
            plays++
            if (fail) error("Device unavailable")
            this.pcm = pcm
        }
        override fun stop() { stops++ }
    }

    private val tone = BuzzerTone(true, 440, 1000.0)

    @Test
    fun unchangedToneDoesNotRestartTheNativePlayer() {
        val players = mutableListOf<Player>()
        val session = ExtraIoAudioSession({ Player().also { players += it } }) { error(it) }
        session.update(mapOf("instance/buzzer" to tone), true)
        session.update(mapOf("instance/buzzer" to tone.copy()), true)
        assertEquals(1, players.size)
        assertEquals(1, players.single().plays)
        assertEquals(0, players.single().stops)
        assertTrue(players.single().pcm!!.stereoSamples.isNotEmpty())
        session.close()
    }

    @Test
    fun removingAnInstanceStopsOnlyItsPlayer() {
        val players = mutableListOf<Player>()
        val session = ExtraIoAudioSession({ Player().also { players += it } }) { error(it) }
        session.update(linkedMapOf("first/buzzer" to tone, "second/buzzer" to tone), true)
        session.update(mapOf("second/buzzer" to tone), true)
        assertEquals(listOf(1, 0), players.map { it.stops })
        session.close()
        assertEquals(listOf(1, 1), players.map { it.stops })
    }

    @Test
    fun changingParametersStopsTheOldPlayerBeforeReplacingIt() {
        val calls = mutableListOf<String>()
        val session = ExtraIoAudioSession({ object : ExtraIoAudioPlayer {
            override fun play(pcm: BuzzerPcm) { calls += "play" }
            override fun stop() { calls += "stop" }
        } }) { error(it) }
        session.update(mapOf("buzzer" to tone), true)
        session.update(mapOf("buzzer" to tone.copy(frequencyHz = 1000)), true)
        assertEquals(listOf("play", "stop", "play"), calls)
        session.close()
    }

    @Test
    fun deactivationAndDisposalStopAudioAndClosedSessionsCannotRestart() {
        val player = Player()
        val session = ExtraIoAudioSession({ player }) { error(it) }
        session.update(mapOf("buzzer" to tone), true)
        session.update(mapOf("buzzer" to tone), false)
        assertEquals(1, player.stops)
        session.close()
        session.close()
        session.update(mapOf("buzzer" to tone), true)
        assertEquals(1, player.plays)
        assertEquals(1, player.stops)
    }

    @Test
    fun aDeviceFailureIsReportedOnceUntilTheToneOrActivationChanges() {
        val players = mutableListOf<Player>()
        val errors = mutableListOf<LocalizedText>()
        val session = ExtraIoAudioSession({ Player(true).also { players += it } }, errors::add)
        session.update(mapOf("buzzer" to tone), true)
        session.update(mapOf("buzzer" to tone), true)
        assertEquals(listOf("Device unavailable"), errors.map { it.resolve(AppLanguage.ENGLISH) })
        assertEquals(1, players.single().stops)
        session.update(emptyMap(), false)
        session.update(mapOf("buzzer" to tone), true)
        assertEquals(2, errors.size)
        session.close()
    }

    @Test
    fun deviceFailureRetainsItsIdentityWhenTheAppLanguageChanges() {
        val errors = mutableListOf<LocalizedText>()
        val session = ExtraIoAudioSession({ object : ExtraIoAudioPlayer {
            override fun play(pcm: BuzzerPcm) {
                throw LocalizedStateException(message("extra_io_audio_start_failed"))
            }
            override fun stop() = Unit
        } }, errors::add)
        session.update(mapOf("buzzer" to tone), true)
        val failure = errors.single()
        assertEquals("extra_io_audio_start_failed", failure.key)
        assertEquals("The device could not start audio output.", failure.resolve(AppLanguage.ENGLISH))
        assertEquals("O dispositivo não iniciou a saída de áudio.", failure.resolve(AppLanguage.PORTUGUESE))
        session.close()
    }

    @Test
    fun missingProviderDetailsUseTheLocalizedFallback() {
        val errors = mutableListOf<LocalizedText>()
        val session = ExtraIoAudioSession({ object : ExtraIoAudioPlayer {
            override fun play(pcm: BuzzerPcm) { throw IllegalStateException() }
            override fun stop() = Unit
        } }, errors::add)
        session.update(mapOf("buzzer" to tone), true)
        val failure = errors.single()
        assertEquals("extra_io_audio_failed", failure.key)
        assertEquals("Could not play the buzzer.", failure.resolve(AppLanguage.ENGLISH))
        assertEquals("Não foi possível reproduzir o buzzer.", failure.resolve(AppLanguage.PORTUGUESE))
        session.close()
    }
}
