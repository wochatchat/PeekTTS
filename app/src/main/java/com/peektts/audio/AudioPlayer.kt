package com.peektts.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * AudioPlayer — 播放 TTS 生成的 PCM 音频数据
 *
 * 使用 AudioTrack 流式播放，支持中途停止（语音打断）。
 */
class AudioPlayer(
    private val sampleRate: Int = 24000,
    private val channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
) {
    private val tag = "AudioPlayer"
    private var audioTrack: AudioTrack? = null

    @Volatile
    var isPlaying = false
        private set

    fun play(pcmData: ShortArray, sampleRate: Int = this.sampleRate) {
        stop()

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, pcmData.size)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.let { track ->
            track.write(pcmData, 0, pcmData.size)
            track.notificationMarkerPosition = pcmData.size
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) {
                    isPlaying = false
                    Log.d(tag, "Playback finished")
                }
                override fun onPeriodicNotification(track: AudioTrack?) {}
            })
            isPlaying = true
            track.play()
            Log.i(tag, "Playing ${pcmData.size} samples at ${sampleRate}Hz")
        }
    }

    fun stop() {
        isPlaying = false
        audioTrack?.let { track ->
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            } catch (e: Exception) {
                Log.e(tag, "Error stopping audio track", e)
            }
        }
        audioTrack = null
    }
}
