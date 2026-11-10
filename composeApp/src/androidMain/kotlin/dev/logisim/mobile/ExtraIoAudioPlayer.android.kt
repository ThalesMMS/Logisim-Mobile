package dev.logisim.mobile

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.logisim.mobile.core.BuzzerPcm
import dev.logisim.mobile.core.localization.checkLocalized
import dev.logisim.mobile.core.localization.message

actual fun createExtraIoAudioPlayer(): ExtraIoAudioPlayer = AndroidExtraIoAudioPlayer()

private class AndroidExtraIoAudioPlayer : ExtraIoAudioPlayer {
    private var track: AudioTrack? = null

    override fun play(pcm: BuzzerPcm) {
        stop()
        if (pcm.stereoSamples.isEmpty()) return
        val next = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(pcm.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.stereoSamples.size * 2)
            .build()
        track = next
        checkLocalized(next.state == AudioTrack.STATE_INITIALIZED) { message("extra_io_audio_initialize_failed") }
        checkLocalized(next.write(pcm.stereoSamples, 0, pcm.stereoSamples.size) == pcm.stereoSamples.size) { message("extra_io_audio_transfer_failed") }
        checkLocalized(next.setLoopPoints(0, pcm.stereoSamples.size / 2, -1) == AudioTrack.SUCCESS) { message("extra_io_audio_loop_failed") }
        next.play()
    }

    override fun stop() {
        track?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.stop()
            it.release()
        }
        track = null
    }
}
