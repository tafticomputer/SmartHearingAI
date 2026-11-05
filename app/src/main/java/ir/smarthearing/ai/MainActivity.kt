package ir.smarthearing.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var toggle: Button
    private lateinit var status: TextView
    private lateinit var latency: TextView
    private lateinit var inMeter: ProgressBar
    private lateinit var outMeter: ProgressBar

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        root.addView(TextView(this).apply {
            text = "سمعک هوشمند"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "⚠ حتماً از هدفون یا سمعک بلوتوثی استفاده کنید؛ با بلندگوی گوشی صدا سوت می‌کشد."
            textSize = 13f
            setTextColor(Color.parseColor("#B45309"))
            setPadding(0, dp(8), 0, dp(12))
        })

        toggle = Button(this).apply {
            text = "شروع"
            textSize = 18f
            setOnClickListener { onToggle() }
        }
        root.addView(toggle)

        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(12), 0, 0) }
        latency = TextView(this).apply { textSize = 15f }
        root.addView(status)
        root.addView(latency)

        root.addView(label("سطح صدای ورودی"))
        inMeter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(inMeter)
        root.addView(label("سطح صدای خروجی"))
        outMeter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(outMeter)

        addSlider(root, "کنترل نویز (حذف خش‌خش)", AudioEngine.noiseReduction) { AudioEngine.noiseReduction = it }
        addSlider(root, "تقویت گفتار", AudioEngine.speechBoost) { AudioEngine.speechBoost = it }
        addSlider(root, "حذف صدای باد", AudioEngine.windCut) { AudioEngine.windCut = it }
        addSlider(root, "بم", AudioEngine.bass) { AudioEngine.bass = it }
        addSlider(root, "زیر", AudioEngine.treble) { AudioEngine.treble = it }
        addSlider(root, "کنترل صدا (بلندی خروجی)", AudioEngine.volume) { AudioEngine.volume = it }

        root.addView(TextView(this).apply {
            text = "این برنامه جایگزین تنظیم تخصصی سمعک نیست و فقط برای آزمایش است. " +
                "صدا را از مقدار کم شروع کنید."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(20), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(0, dp(14), 0, dp(2))
    }

    private fun addSlider(parent: LinearLayout, title: String, initial: Float, onChange: (Float) -> Unit) {
        parent.addView(label(title))
        parent.addView(SeekBar(this).apply {
            max = 100
            progress = (initial * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    onChange(p / 100f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }

    private fun onToggle() {
        if (AudioEngine.running) {
            startService(Intent(this, HearingService::class.java).setAction(HearingService.ACTION_STOP))
            return
        }
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) needed += Manifest.permission.BLUETOOTH_CONNECT
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
        } else {
            startEngine()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startEngine()
        } else {
            Toast.makeText(this, "برای کار برنامه مجوز میکروفون لازم است", Toast.LENGTH_LONG).show()
        }
    }

    private fun startEngine() {
        startForegroundService(Intent(this, HearingService::class.java))
    }

    private fun refresh() {
        val on = AudioEngine.running
        toggle.text = if (on) "توقف" else "شروع"
        val err = AudioEngine.lastError
        status.text = when {
            err != null -> "وضعیت: $err"
            on && AudioEngine.aiActive -> "وضعیت: فعال (حذف نویز با هوش مصنوعی RNNoise)"
            on -> "وضعیت: فعال (حالت ساده، بدون هوش مصنوعی)"
            else -> "وضعیت: متوقف"
        }
        latency.text = if (on) "تأخیر تقریبی: ${AudioEngine.latencyMs} میلی‌ثانیه" else "تأخیر تقریبی: —"
        inMeter.progress = (AudioEngine.inputLevel * 100).toInt()
        outMeter.progress = (AudioEngine.outputLevel * 100).toInt()
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
