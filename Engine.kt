package app.soundpad

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.sin

object Engine {
    const val RATE = 48000
    const val FRAME = 480

    private class Voice(val pad: String, val pcm: ShortArray, val vol: Float, val loop: Boolean) {
        var pos = 0
    }

    private val voices = CopyOnWriteArrayList<Voice>()
    private val cache = ConcurrentHashMap<String, ShortArray>()
    private val pool = Executors.newSingleThreadExecutor()

    @Volatile
    var injecting = false

    @Volatile
    var note = ""

    @Volatile
    var micPeak = 0

    private var out: OutputStream? = null
    private var runner: Runner? = null
    private var micAllowed = true

    private val toneData: ShortArray by lazy {
        val s = ShortArray(RATE * 3)
        for (i in s.indices) {
            val t = i % (RATE / 2)
            val on = t < RATE * 3 / 10
            s[i] = if (on) (sin(2 * PI * 1000 * i / RATE) * 9000).toInt().toShort() else 0
        }
        s
    }

    fun preload() {
        pool.execute {
            for (p in ArrayList(Store.pads)) load(p)
        }
    }

    private fun load(p: Pad): ShortArray? {
        cache[p.id]?.let { return it }
        val d = Pcm.decode(File(Store.dir, p.file).path)
        if (d == null || d.isEmpty()) {
            note = "не удалось прочитать «${p.name}»"
            return null
        }
        cache[p.id] = d
        return d
    }

    fun trigger(p: Pad) {
        val live = voices.filter { it.pad == p.id }
        if (p.loop && live.isNotEmpty()) {
            voices.removeAll(live)
            return
        }
        val c = cache[p.id]
        if (c != null) {
            voices.add(Voice(p.id, c, p.volume, p.loop))
        } else {
            pool.execute {
                load(p)?.let { voices.add(Voice(p.id, it, p.volume, p.loop)) }
            }
        }
    }

    fun tone() {
        voices.add(Voice("tone", toneData, 1f, false))
    }

    fun stopAll() {
        voices.clear()
    }

    fun forget(p: Pad) {
        cache.remove(p.id)
        voices.removeAll(voices.filter { it.pad == p.id })
    }

    @Synchronized
    fun startInject(inj: IInjector): String {
        if (injecting) return "ok"
        val pipe = ParcelFileDescriptor.createPipe()
        val res = try {
            inj.start(pipe[0], RATE, Process.myUid())
        } catch (e: Throwable) {
            "ошибка вызова: ${e.message}"
        }
        try {
            pipe[0].close()
        } catch (_: Exception) {
        }
        if (!res.startsWith("ok")) {
            try {
                pipe[1].close()
            } catch (_: Exception) {
            }
            return res
        }
        micAllowed = res == "ok"
        out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        injecting = true
        restart()
        if (!micAllowed && Store.mic) note = "Исключение своего процесса не поддерживается, голос выключен"
        return res
    }

    @Synchronized
    fun stopInject() {
        injecting = false
        restart()
        try {
            out?.close()
        } catch (_: Exception) {
        }
        out = null
        val inj = Shell.injector
        if (inj != null) {
            Thread {
                try {
                    inj.stop()
                } catch (_: Throwable) {
                }
            }.start()
        }
    }

    fun injectorLost() {
        injecting = false
        restart()
        note = "Shizuku-сервис отвалился"
    }

    @Synchronized
    fun restart() {
        runner?.alive = false
        runner?.join(300)
        runner = null
        if (!injecting && !Store.monitor) return
        runner = Runner(if (injecting) out else null, injecting && Store.mic && micAllowed, Store.monitor).also {
            it.start()
        }
    }

    private fun clamp(x: Int): Int = if (x > 32767) 32767 else if (x < -32768) -32768 else x

    private class Runner(
        val sink: OutputStream?,
        val useMic: Boolean,
        val monitor: Boolean,
    ) : Thread("mixer") {
        @Volatile
        var alive = true

        @SuppressLint("MissingPermission")
        private fun openMic(): AudioRecord? {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 16)
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return null
            }
            r.startRecording()
            return r
        }

        private fun openLocal(): AudioTrack {
            val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, FRAME * 8))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            return t
        }

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var mic: AudioRecord? = null
            var local: AudioTrack? = null
            try {
                if (useMic) {
                    mic = openMic()
                    if (mic == null) note = "микрофон не открылся"
                }
                if (monitor) local = openLocal()
                val fx = IntArray(FRAME)
                val micBuf = ShortArray(FRAME)
                val loc = ShortArray(FRAME)
                val bytes = ByteArray(FRAME * 2)
                var next = System.nanoTime()

                while (alive) {
                    java.util.Arrays.fill(fx, 0)
                    mixVoices(fx)

                    if (mic != null) {
                        var got = 0
                        while (got < FRAME && alive) {
                            val n = mic.read(micBuf, got, FRAME - got)
                            if (n < 0) {
                                note = "ошибка чтения микрофона ($n)"
                                mic.release()
                                mic = null
                                java.util.Arrays.fill(micBuf, 0)
                                break
                            }
                            got += n
                        }
                        var peak = 0
                        for (v in micBuf) {
                            val a = Math.abs(v.toInt())
                            if (a > peak) peak = a
                        }
                        micPeak = peak
                    }

                    for (i in 0 until FRAME) {
                        val m = if (mic != null) micBuf[i].toInt() else 0
                        val e = fx[i]
                        loc[i] = clamp(e).toShort()
                        val v = clamp(e + m)
                        bytes[2 * i] = (v and 0xff).toByte()
                        bytes[2 * i + 1] = (v shr 8).toByte()
                    }

                    if (sink != null) {
                        try {
                            sink.write(bytes)
                        } catch (e: Exception) {
                            injecting = false
                            note = "канал в сервис закрыт"
                            break
                        }
                    }
                    local?.write(loc, 0, FRAME)

                    if (mic == null) {
                        next += 10_000_000L
                        val d = next - System.nanoTime()
                        if (d > 0) LockSupport.parkNanos(d)
                        else if (d < -100_000_000L) next = System.nanoTime()
                    }
                }
            } catch (e: Throwable) {
                note = "mixer: ${e.message}"
                injecting = false
            } finally {
                try {
                    mic?.stop()
                } catch (_: Exception) {
                }
                mic?.release()
                try {
                    local?.stop()
                } catch (_: Exception) {
                }
                local?.release()
            }
        }

        private fun mixVoices(fx: IntArray) {
            val master = Store.master
            for (v in voices) {
                val src = v.pcm
                if (src.isEmpty()) {
                    voices.remove(v)
                    continue
                }
                val gain = v.vol * master
                var i = 0
                while (i < FRAME) {
                    if (v.pos >= src.size) {
                        if (v.loop) v.pos = 0 else break
                    }
                    fx[i] += (src[v.pos] * gain).toInt()
                    v.pos++
                    i++
                }
                if (!v.loop && v.pos >= src.size) voices.remove(v)
            }
        }
    }
}
