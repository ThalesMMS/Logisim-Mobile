package dev.logisim.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.logisim.mobile.core.BuzzerPcm
import dev.logisim.mobile.core.BuzzerTone
import dev.logisim.mobile.core.localization.LocalizedText
import dev.logisim.mobile.core.localization.localizedDescription
import dev.logisim.mobile.core.localization.message

interface ExtraIoAudioPlayer {
    fun play(pcm: BuzzerPcm)
    fun stop()
}

expect fun createExtraIoAudioPlayer(): ExtraIoAudioPlayer

class ExtraIoAudioSession(private val createPlayer: () -> ExtraIoAudioPlayer, private val onFailure: (LocalizedText) -> Unit) {
    private data class Playback(val tone: BuzzerTone, val player: ExtraIoAudioPlayer?)
    private val players = mutableMapOf<String, Playback>()
    private var closed = false

    fun update(tones: Map<String, BuzzerTone>, enabled: Boolean) {
        if (closed) return
        val desired = if (enabled) tones.filterValues { it.audible } else emptyMap()
        players.keys.toList().filter { desired[it] != players[it]?.tone }.forEach { id -> players.remove(id)?.player?.stop() }
        for ((id, tone) in desired) {
            if (id in players) continue
            val player = createPlayer()
            try {
                player.play(tone.pcm())
                players[id] = Playback(tone, player)
            } catch (error: Exception) {
                player.stop()
                players[id] = Playback(tone, null)
                onFailure(error.localizedDescription(message("extra_io_audio_failed")))
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        players.values.forEach { it.player?.stop() }
        players.clear()
    }
}

/** A live circuit instance owns its player until its tone changes or its session closes. */
@Composable
fun ExtraIoAudioEffects(tones: Map<String, BuzzerTone>, enabled: Boolean, onFailure: (LocalizedText) -> Unit) {
    val failureHandler by rememberUpdatedState(onFailure)
    val session = remember { ExtraIoAudioSession(::createExtraIoAudioPlayer) { failureHandler(it) } }
    DisposableEffect(session) { onDispose { session.close() } }
    SideEffect { session.update(tones, enabled) }
}
