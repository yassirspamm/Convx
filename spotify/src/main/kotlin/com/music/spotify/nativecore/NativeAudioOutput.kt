package com.music.spotify.nativecore

import java.nio.ByteBuffer

interface NativeAudioOutput {
    fun start()
    fun stop()
    fun write(buffer: ByteBuffer, size: Int, sampleRate: Int, channels: Int)
    fun writeVoice(buffer: ByteBuffer, size: Int, sampleRate: Int, channels: Int) = write(buffer, size, sampleRate, channels)
}
