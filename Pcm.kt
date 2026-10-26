package app.soundpad

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteOrder

object Pcm {
    private const val LIMIT = 48000 * 2 * 90

    /** Декодирует файл в 16-бит моно 48 кГц. */
    fun decode(path: String): ShortArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(path)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    idx = i
                    fmt = f
                    break
                }
            }
            if (idx < 0 || fmt == null) return null
            ex.selectTrack(idx)
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()

            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            val chunks = ArrayList<ShortArray>()
            var total = 0
            var inDone = false
            var outDone = false

            while (!outDone && total < LIMIT) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val arr = ShortArray(sb.remaining())
                    sb.get(arr)
                    chunks.add(arr)
                    total += arr.size
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            if (total == 0 || ch < 1) return null

            val all = ShortArray(total)
            var pos = 0
            for (c in chunks) {
                System.arraycopy(c, 0, all, pos, c.size)
                pos += c.size
            }
            val frames = total / ch
            val outLen = (frames.toLong() * Engine.RATE / rate).toInt()
            val out = ShortArray(outLen)
            val step = rate.toDouble() / Engine.RATE
            for (j in 0 until outLen) {
                val p = j * step
                val i0 = p.toInt()
                val fr = (p - i0).toFloat()
                val a = at(all, i0, ch)
                val b = if (i0 + 1 < frames) at(all, i0 + 1, ch) else a
                out[j] = (a + (b - a) * fr).toInt().toShort()
            }
            return out
        } catch (e: Exception) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            ex.release()
        }
    }

    private fun at(all: ShortArray, frame: Int, ch: Int): Int {
        var s = 0
        val base = frame * ch
        for (c in 0 until ch) s += all[base + c]
        return s / ch
    }
}
