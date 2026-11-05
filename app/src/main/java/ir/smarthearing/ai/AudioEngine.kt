package ir.smarthearing.ai

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * موتور صدا: میکروفون -> بالاگذر (باد) -> RNNoise (هوش مصنوعی) -> تقویت گفتار/EQ -> خروجی.
 * پارامترها همگی بین ۰ تا ۱ هستند و هر لحظه قابل تغییرند.
 */
object AudioEngine {

    private const val SAMPLE_RATE = 48000
    private const val FRAME = RNNoise.FRAME // ۴۸۰ نمونه = ۱۰ میلی‌ثانیه

    @Volatile var noiseReduction = 0.7f   // میزان حذف نویز
    @Volatile var speechBoost = 0.4f      // تقویت گفتار
    @Volatile var windCut = 0.3f          // حذف صدای باد
    @Volatile var bass = 0.5f             // بم (۰.۵ = خنثی)
    @Volatile var treble = 0.5f           // زیر (۰.۵ = خنثی)
    @Volatile var volume = 0.4f           // بلندی خروجی

    // ---- حالت خودکار ----
    const val P_NOISE = 0
    const val P_SPEECH = 1
    const val P_WIND = 2
    const val P_BASS = 3
    const val P_TREBLE = 4
    const val P_VOLUME = 5
    const val PARAM_COUNT = 6

    /** اگر روشن باشد، برنامه پارامترهای «قفل‌نشده» را خودش تنظیم می‌کند. */
    @Volatile var autoMode = true
    /** پارامتری که کاربر دستی تغییر داده قفل می‌شود و خودکار دست نمی‌زند. */
    private val locked = BooleanArray(PARAM_COUNT)
    @Volatile var envText = "—"
        private set

    fun getParam(i: Int): Float = when (i) {
        P_NOISE -> noiseReduction
        P_SPEECH -> speechBoost
        P_WIND -> windCut
        P_BASS -> bass
        P_TREBLE -> treble
        else -> volume
    }

    fun setParam(i: Int, v: Float) {
        val c = v.coerceIn(0f, 1f)
        when (i) {
            P_NOISE -> noiseReduction = c
            P_SPEECH -> speechBoost = c
            P_WIND -> windCut = c
            P_BASS -> bass = c
            P_TREBLE -> treble = c
            else -> volume = c
        }
    }

    fun setLocked(i: Int, l: Boolean) { locked[i] = l }
    fun isLocked(i: Int): Boolean = locked[i]
    fun unlockAll() { for (i in 0 until PARAM_COUNT) locked[i] = false }

    /** حرکت نرم پارامتر به‌سوی مقدار هدف (فقط اگر کاربر قفلش نکرده باشد) */
    private fun autoStep(i: Int, target: Float) {
        if (locked[i]) return
        val cur = getParam(i)
        setParam(i, cur + 0.004f * (target.coerceIn(0f, 1f) - cur))
    }

    @Volatile var running = false
        private set
    @Volatile var inputLevel = 0f
        private set
    @Volatile var outputLevel = 0f
        private set
    @Volatile var latencyMs = 0
        private set
    @Volatile var aiActive = false
        private set
    @Volatile var lastError: String? = null
        private set

    private var thread: Thread? = null

    fun start() {
        if (running) return
        lastError = null
        running = true
        thread = Thread({ loop() }, "AudioEngine").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    fun stop() {
        running = false
        try { thread?.join(1500) } catch (_: InterruptedException) { }
        thread = null
        inputLevel = 0f
        outputLevel = 0f
    }

    private class OnePole(private val cutoffHz: Float) {
        private val a = 1f - Math.exp(-2.0 * Math.PI * cutoffHz / SAMPLE_RATE).toFloat()
        private var y = 0f
        fun lp(x: Float): Float { y += a * (x - y); return y }
    }

    private fun loop() {
        val inMin = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val outMin = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (inMin <= 0 || outMin <= 0) {
            lastError = "فرمت صدا روی این گوشی پشتیبانی نمی‌شود"
            running = false
            return
        }

        val recorder: AudioRecord
        val track: AudioTrack
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(inMin, FRAME * 2 * 2)
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(outMin, FRAME * 2 * 2))
                .build()
        } catch (e: Exception) {
            lastError = "خطا در راه‌اندازی صدا: ${e.message}"
            running = false
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            lastError = "میکروفون در دسترس نیست (مجوز را بررسی کنید)"
            running = false
            recorder.release(); track.release()
            return
        }

        var ns: NoiseSuppressor? = null
        var agc: AutomaticGainControl? = null
        try {
            // اگر RNNoise در دسترس نیست، از نویزگیر سخت‌افزاری گوشی استفاده می‌کنیم
            if (!RNNoise.available && NoiseSuppressor.isAvailable())
                ns = NoiseSuppressor.create(recorder.audioSessionId)?.also { it.enabled = true }
            if (AutomaticGainControl.isAvailable())
                agc = AutomaticGainControl.create(recorder.audioSessionId)?.also { it.enabled = false }
        } catch (_: Exception) { }

        // مدل هوش مصنوعی
        val handle: Long = if (RNNoise.available) {
            try { RNNoise.create() } catch (_: Throwable) { 0L }
        } else 0L
        aiActive = handle != 0L

        latencyMs = ((recorder.bufferSizeInFrames + track.bufferSizeInFrames) * 1000L / SAMPLE_RATE).toInt() +
            (if (aiActive) 10 else 0)

        val lpBass = OnePole(300f)
        val lpLow = OnePole(1000f)
        val lpHigh = OnePole(3500f)
        val lpTreble = OnePole(3000f)
        val hpState = FloatArray(2)
        val hpPrevIn = FloatArray(2)
        var envelope = 0f
        var noiseFloor = 0.01f
        var gateGain = 1f
        var vadGain = 1f

        // تحلیل محیط برای حالت خودکار
        val lpWind = OnePole(200f)
        val lpHf = OnePole(3000f)
        var noiseTrackDb = -70f     // فقط برای حالت بدون AI
        var noiseDbS = -70f         // سطح نویز محیط (dBFS)
        var speechDbS = -40f        // سطح گفتار (dBFS)
        var windS = 0.2f            // سهم انرژی فرکانس‌های خیلی پایین در نویز
        var hfS = 0.05f             // سهم انرژی فرکانس‌های بالا در گفتار
        var framesSinceSpeech = 100000
        var frameCount = 0

        val inBuf = ShortArray(FRAME)
        val outBuf = ShortArray(FRAME)
        val dry = FloatArray(FRAME)
        val wet = FloatArray(FRAME)

        try {
            recorder.startRecording()
            track.play()

            while (running) {
                // خواندن یک فریم کامل (۴۸۰ نمونه)
                var got = 0
                while (got < FRAME && running) {
                    val n = recorder.read(inBuf, got, FRAME - got)
                    if (n < 0) throw IllegalStateException("خطای خواندن میکروفون ($n)")
                    got += n
                }
                if (!running) break

                val nr = noiseReduction
                val sb = speechBoost
                val wc = windCut
                val bassG = (bass - 0.5f) * 4f              // -2 .. +2
                val trebG = (treble - 0.5f) * 4f
                val outGain = 0.2f + volume * 4.8f          // 0.2 .. 5
                val hpCut = 40f + wc * 260f
                val hpA = Math.exp(-2.0 * Math.PI * hpCut / SAMPLE_RATE).toFloat()

                var inPeak = 0f
                var outPeak = 0f

                // ۱) بالاگذر (حذف باد) — مقیاس ۱۶ بیتی برای RNNoise
                var eRaw = 0f
                var eLow = 0f
                var eHf = 0f
                for (i in 0 until FRAME) {
                    var x = inBuf[i].toFloat()
                    inPeak = max(inPeak, abs(x) / 32768f)
                    // آمار صدای خام برای تحلیل محیط
                    val xn = x / 32768f
                    eRaw += xn * xn
                    val lw = lpWind.lp(xn)
                    eLow += lw * lw
                    val hf = xn - lpHf.lp(xn)
                    eHf += hf * hf
                    for (s in 0..1) {
                        val y = hpA * (hpState[s] + x - hpPrevIn[s])
                        hpPrevIn[s] = x
                        hpState[s] = y
                        x = y
                    }
                    dry[i] = x
                }

                // ۲) حذف نویز با هوش مصنوعی
                var vad = 1f
                if (aiActive) {
                    vad = RNNoise.process(handle, dry, wet)
                } else {
                    System.arraycopy(dry, 0, wet, 0, FRAME)
                }
                // در سکوت/نویز، کمی بیشتر کم می‌کنیم
                val vadTarget = if (aiActive) 1f - nr * 0.6f * (1f - vad) else 1f

                // ---- تحلیل محیط و تنظیم خودکار ----
                val rmsDb = (20.0 * Math.log10(Math.sqrt((eRaw / FRAME).toDouble()) + 1e-9)).toFloat()
                val lowRatio = eLow / (eRaw + 1e-12f)
                val hfRatio = eHf / (eRaw + 1e-12f)
                val speechProb: Float = if (aiActive) vad else {
                    noiseTrackDb = if (rmsDb < noiseTrackDb) noiseTrackDb + 0.1f * (rmsDb - noiseTrackDb)
                                   else noiseTrackDb + 0.01f
                    if (rmsDb > noiseTrackDb + 8f) 1f else 0f
                }
                if (rmsDb > -85f) {
                    if (speechProb < 0.3f) {
                        noiseDbS += 0.02f * (rmsDb - noiseDbS)
                        windS += 0.02f * (lowRatio - windS)
                    } else if (speechProb > 0.7f) {
                        speechDbS += 0.05f * (rmsDb - speechDbS)
                        hfS += 0.03f * (hfRatio - hfS)
                        framesSinceSpeech = 0
                    }
                }
                if (framesSinceSpeech < 100000) framesSinceSpeech++

                val noiseF = ((noiseDbS + 65f) / 30f).coerceIn(0f, 1f)
                val windF = ((windS - 0.6f) / 0.35f).coerceIn(0f, 1f) *
                    ((noiseDbS + 70f) / 15f).coerceIn(0f, 1f)
                val dull = ((0.06f - hfS) / 0.06f).coerceIn(0f, 1f)

                if (autoMode) {
                    autoStep(P_NOISE, 0.2f + 0.7f * noiseF)
                    autoStep(P_WIND, 0.15f + 0.8f * windF)
                    autoStep(P_SPEECH, 0.2f + 0.6f * ((-25f - speechDbS) / 25f).coerceIn(0f, 1f))
                    autoStep(P_BASS, (0.5f - 0.2f * noiseF - 0.15f * windF).coerceIn(0.2f, 0.5f))
                    autoStep(P_TREBLE, 0.55f + 0.2f * dull * (1f - 0.5f * noiseF))
                    // بلندی: فقط وقتی اخیراً گفتار بوده، تا گفتار به سطح راحت (حدود -۲۴ dBFS) برسد
                    if (framesSinceSpeech < 300) {
                        val needDb = -24f - speechDbS
                        val g = Math.pow(10.0, (needDb / 20f).toDouble()).toFloat()
                        autoStep(P_VOLUME, ((g - 0.2f) / 4.8f).coerceIn(0.15f, 0.75f))
                    }
                }

                frameCount++
                if (frameCount % 50 == 0) {
                    val kind = when {
                        windF > 0.5f -> "باد/لرزش"
                        noiseDbS > -40f -> "شلوغ"
                        noiseDbS > -55f -> "متوسط"
                        else -> "ساکت"
                    }
                    envText = "محیط: $kind  •  نویز ${noiseDbS.toInt()} dB  •  گفتار ${speechDbS.toInt()} dB"
                }

                for (i in 0 until FRAME) {
                    // ترکیب سیگنال خام و پاک‌شده بر اساس «کنترل نویز»
                    var x = (dry[i] * (1f - nr) + wet[i] * nr) / 32768f

                    if (aiActive) {
                        vadGain += 0.002f * (vadTarget - vadGain)
                        x *= vadGain
                    } else {
                        // جایگزین ساده بدون AI: گیت نویز تطبیقی
                        val ax = abs(x)
                        envelope += (if (ax > envelope) 0.02f else 0.0015f) * (ax - envelope)
                        noiseFloor = if (envelope < noiseFloor) {
                            envelope + (noiseFloor - envelope) * 0.9995f
                        } else noiseFloor * 1.00002f
                        noiseFloor = noiseFloor.coerceIn(0.0005f, 0.2f)
                        val threshold = noiseFloor * (1.5f + 2.5f * nr)
                        val target = if (envelope > threshold) 1f else (1f - nr * 0.92f)
                        gateGain += (if (target > gateGain) 0.01f else 0.0008f) * (target - gateGain)
                        x *= gateGain
                    }

                    // ۳) تقویت باند گفتار (۱ تا ۳.۵ کیلوهرتز)
                    x += (lpHigh.lp(x) - lpLow.lp(x)) * (sb * 2.5f)

                    // ۴) بم و زیر
                    x += lpBass.lp(x) * bassG
                    x += (x - lpTreble.lp(x)) * trebG

                    // ۵) بلندی + محدودکنندهٔ نرم
                    x = tanh(x * outGain)
                    outPeak = max(outPeak, abs(x))
                    outBuf[i] = (x * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }

                inputLevel = min(1f, inPeak * 2f)
                outputLevel = min(1f, outPeak)
                track.write(outBuf, 0, FRAME)
            }
        } catch (e: Exception) {
            lastError = "خطا: ${e.message}"
        } finally {
            try { recorder.stop() } catch (_: Exception) { }
            recorder.release()
            try { track.stop() } catch (_: Exception) { }
            track.release()
            ns?.release(); agc?.release()
            if (handle != 0L) RNNoise.destroy(handle)
            aiActive = false
            running = false
        }
    }
}
