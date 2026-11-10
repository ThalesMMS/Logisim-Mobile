package dev.logisim.mobile

import dev.logisim.mobile.core.BuzzerPcm
import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.message
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSData
import platform.Foundation.create

actual fun createExtraIoAudioPlayer(): ExtraIoAudioPlayer = IosExtraIoAudioPlayer()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosExtraIoAudioPlayer : ExtraIoAudioPlayer {
    private var player: AVAudioPlayer? = null

    override fun play(pcm: BuzzerPcm) {
        stop()
        if (pcm.stereoSamples.isEmpty()) return
        val wav = pcm.wav()
        val data = wav.usePinned { NSData.create(bytes = it.addressOf(0), length = wav.size.toULong()) }
        val next = AVAudioPlayer(data = data, error = null)
        next.numberOfLoops = -1
        checkLocalized(next.prepareToPlay()) { message("extra_io_audio_prepare_failed") }
        player = next
        checkLocalized(next.play()) { message("extra_io_audio_start_failed") }
    }

    override fun stop() {
        player?.stop()
        player = null
    }
}
