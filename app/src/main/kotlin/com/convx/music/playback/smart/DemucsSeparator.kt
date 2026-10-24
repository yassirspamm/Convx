package com.convx.music.playback.smart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import com.convx.music.utils.YTPlayerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

enum class StemQuality { FAST, DEMUCS }

enum class DemucsInstall { OK, DOWNLOAD_FAILED, INCOMPATIBLE }

/** The optional htdemucs ONNX model: downloaded on request and checked before it is trusted. */
object DemucsModel {
    private fun file(context: Context) = File(context.filesDir, "demucs/htdemucs.onnx")

    fun isInstalled(context: Context) = file(context).let { it.exists() && it.length() > 0 }

    fun sizeBytes(context: Context) = file(context).takeIf { it.exists() }?.length() ?: 0L

    fun delete(context: Context) {
        file(context).delete()
    }

    internal fun path(context: Context): String = file(context).absolutePath

    suspend fun install(context: Context, url: String, onProgress: (Float) -> Unit): DemucsInstall =
        withContext(Dispatchers.IO) {
            val target = file(context).also { it.parentFile?.mkdirs() }
            val part = File(target.path + ".part")
            val downloaded = runCatching {
                YTPlayerUtils.httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val total = response.body.contentLength()
                    var done = 0L
                    response.body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (total > 0) onProgress(done.toFloat() / total)
                            }
                        }
                    }
                }
                part.renameTo(target)
            }.getOrDefault(false)
            part.delete()
            if (!downloaded) return@withContext DemucsInstall.DOWNLOAD_FAILED
            val compatible = DemucsSeparator(context).use { it.selfCheck() }
            if (compatible) DemucsInstall.OK else {
                target.delete()
                DemucsInstall.INCOMPATIBLE
            }
        }
}

/**
 * Runs an htdemucs ONNX export that takes the waveform `[1,2,N]` and Demucs' complex-as-channels
 * spectrogram `[1,4,2048,T]` and returns a frequency branch `[1,S,4,2048,T]` plus a time branch
 * `[1,S,2,N]`, sources ordered drums, bass, other, vocals. Anything that does not match is refused.
 */
internal class DemucsSeparator(private val context: Context) : AutoCloseable {
    private class Contract(
        val segment: Int,
        val frames: Int,
        val waveInput: String,
        val specInput: String,
        val freqOutput: Int,
        val timeOutput: Int,
    )

    private var options: OrtSession.SessionOptions? = null
    private var session: OrtSession? = null
    private var contract: Contract? = null

    private fun open(): Boolean {
        if (session != null) return true
        if (!DemucsModel.isInstalled(context)) return false
        return runCatching {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
            }
            val created = OrtEnvironment.getEnvironment().createSession(DemucsModel.path(context), opts)
            options = opts
            session = created
            contract = readContract(created)
            contract != null
        }.onFailure { Log.w(TAG, "Demucs model could not be opened", it) }.getOrDefault(false)
    }

    private fun readContract(session: OrtSession): Contract? {
        fun shapeOf(info: ai.onnxruntime.NodeInfo) = (info.info as? TensorInfo)?.shape
        val inputs = session.inputInfo.mapValues { shapeOf(it.value) }
        val wave = inputs.entries.firstOrNull { it.value?.size == 3 && it.value!![1] == 2L } ?: return null
        val spec = inputs.entries.firstOrNull { it.value?.size == 4 && it.value!![1] == 4L } ?: return null
        val segment = wave.value!![2].toInt()
        val frames = spec.value!![3].toInt()
        if (segment <= 0 || frames != DemucsStft.frames(segment) || spec.value!![2].toInt() != DemucsStft.BINS) return null

        val names = session.outputNames.toList()
        val outputs = session.outputInfo
        val freq = names.indexOfFirst { shapeOf(outputs.getValue(it))?.size == 5 }
        val time = names.indexOfFirst { shapeOf(outputs.getValue(it))?.size == 4 }
        if (freq < 0 || time < 0) return null
        val freqShape = shapeOf(outputs.getValue(names[freq]))!!
        val timeShape = shapeOf(outputs.getValue(names[time]))!!
        if (freqShape[1] != STEMS.toLong() || timeShape[1] != STEMS.toLong() || timeShape[3].toInt() != segment) return null
        return Contract(segment, frames, wave.key, spec.key, freq, time)
    }

    /** One synthetic segment through the model: refused unless the stems carry about the mix's energy. */
    fun selfCheck(): Boolean {
        if (!open()) return false
        val active = contract ?: return false
        val length = active.segment
        val left = FloatArray(length) { (0.3 * Math.sin(it * 0.03) + 0.2 * Math.sin(it * 0.17)).toFloat() }
        val right = FloatArray(length) { (0.3 * Math.sin(it * 0.031) + 0.2 * Math.sin(it * 0.19)).toFloat() }
        val summed = Array(2) { FloatArray(length) }
        val ok = runSegment(left, right, length) { stem, l, r ->
            for (i in 0 until length) {
                summed[0][i] += l[i]
                summed[1][i] += r[i]
            }
        }
        if (!ok) return false
        fun energy(a: FloatArray, from: Int, to: Int): Double {
            var sum = 0.0
            for (i in from until to) sum += a[i].toDouble() * a[i]
            return sqrt(sum / (to - from))
        }
        val from = EDGE_SKIP
        val to = length - EDGE_SKIP
        val mix = energy(left, from, to) + energy(right, from, to)
        val out = energy(summed[0], from, to) + energy(summed[1], from, to)
        val finite = summed.all { channel -> channel.all { it.isFinite() } }
        return finite && mix > 0 && out / mix in 0.25..4.0
    }

    /** Streams every stem as (stem, left, right, count) pieces in track order. False on failure or cancel. */
    fun separate(
        left: FloatArray,
        right: FloatArray,
        isCurrent: () -> Boolean,
        sink: (Int, FloatArray, FloatArray, Int) -> Unit,
    ): Boolean {
        if (!open()) return false
        val active = contract ?: return false
        val n = active.segment
        val stride = n - n / OVERLAP_DIVISOR
        val total = left.size
        val weight = FloatArray(n) { i -> if (i < n / 2) (i + 1).toFloat() else (n - i).toFloat() }
        val peak = weight.max()
        for (i in weight.indices) weight[i] /= peak

        val acc = Array(STEMS) { Array(2) { FloatArray(n) } }
        val sums = FloatArray(n)
        val chunkLeft = FloatArray(n)
        val chunkRight = FloatArray(n)
        val outLeft = FloatArray(n)
        val outRight = FloatArray(n)

        var start = 0
        while (start < total) {
            if (!isCurrent()) return false
            val valid = minOf(n, total - start)
            chunkLeft.fill(0f)
            chunkRight.fill(0f)
            left.copyInto(chunkLeft, 0, start, start + valid)
            right.copyInto(chunkRight, 0, start, start + valid)
            val ok = runSegment(chunkLeft, chunkRight, n) { stem, l, r ->
                for (i in 0 until n) {
                    acc[stem][0][i] += l[i] * weight[i]
                    acc[stem][1][i] += r[i] * weight[i]
                }
            }
            if (!ok) return false
            for (i in 0 until n) sums[i] += weight[i]

            val last = start + n >= total
            val emit = if (last) valid else stride
            for (stem in 0 until STEMS) {
                for (i in 0 until emit) {
                    val w = sums[i].takeIf { it > 1e-6f } ?: 1f
                    outLeft[i] = acc[stem][0][i] / w
                    outRight[i] = acc[stem][1][i] / w
                }
                sink(stem, outLeft, outRight, emit)
            }
            if (last) break
            for (stem in 0 until STEMS) for (channel in 0..1) {
                val a = acc[stem][channel]
                a.copyInto(a, 0, emit, n)
                a.fill(0f, n - emit, n)
            }
            sums.copyInto(sums, 0, emit, n)
            sums.fill(0f, n - emit, n)
            start += stride
        }
        return true
    }

    /** Runs the model on one segment and hands each stem's waveform to [consume]. */
    private fun runSegment(
        left: FloatArray,
        right: FloatArray,
        length: Int,
        consume: (Int, FloatArray, FloatArray) -> Unit,
    ): Boolean {
        val active = contract ?: return false
        val onnx = session ?: return false
        val environment = OrtEnvironment.getEnvironment()
        val frames = active.frames
        val bins = DemucsStft.BINS

        val spec = FloatArray(4 * bins * frames)
        val re = Array(bins) { FloatArray(frames) }
        val im = Array(bins) { FloatArray(frames) }
        for ((channel, samples) in listOf(left, right).withIndex()) {
            DemucsStft.forward(samples, length, re, im)
            for (bin in 0 until bins) {
                re[bin].copyInto(spec, ((2 * channel) * bins + bin) * frames)
                im[bin].copyInto(spec, ((2 * channel + 1) * bins + bin) * frames)
            }
        }
        val wave = FloatArray(2 * length)
        left.copyInto(wave, 0, 0, length)
        right.copyInto(wave, length, 0, length)

        return runCatching {
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(wave), longArrayOf(1, 2, length.toLong())).use { waveTensor ->
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(spec), longArrayOf(1, 4, bins.toLong(), frames.toLong())).use { specTensor ->
                    onnx.run(mapOf(active.waveInput to waveTensor, active.specInput to specTensor)).use { result ->
                        val freq = (result.get(active.freqOutput) as OnnxTensor).floatBuffer
                        val time = (result.get(active.timeOutput) as OnnxTensor).floatBuffer
                        val stemLeft = FloatArray(length)
                        val stemRight = FloatArray(length)
                        val stemRe = Array(bins) { FloatArray(frames) }
                        val stemIm = Array(bins) { FloatArray(frames) }
                        for (stem in 0 until STEMS) {
                            for (channel in 0..1) {
                                for (bin in 0 until bins) {
                                    val reBase = (((stem * 4) + 2 * channel) * bins + bin) * frames
                                    val imBase = (((stem * 4) + 2 * channel + 1) * bins + bin) * frames
                                    for (frame in 0 until frames) {
                                        stemRe[bin][frame] = freq.get(reBase + frame)
                                        stemIm[bin][frame] = freq.get(imBase + frame)
                                    }
                                }
                                val target = if (channel == 0) stemLeft else stemRight
                                DemucsStft.inverse(stemRe, stemIm, length, target)
                                val timeBase = ((stem * 2) + channel) * length
                                for (i in 0 until length) target[i] += time.get(timeBase + i)
                            }
                            consume(stem, stemLeft, stemRight)
                        }
                    }
                }
            }
            true
        }.onFailure { Log.w(TAG, "Demucs inference failed", it) }.getOrDefault(false)
    }

    override fun close() {
        runCatching { session?.close() }
        runCatching { options?.close() }
        session = null
        options = null
    }

    companion object {
        private const val TAG = "ConvxDemucs"
        const val STEMS = 4
        const val DRUMS = 0
        const val BASS = 1
        const val OTHER = 2
        const val VOCALS = 3
        private const val OVERLAP_DIVISOR = 4
        private const val EDGE_SKIP = 4096
    }
}
