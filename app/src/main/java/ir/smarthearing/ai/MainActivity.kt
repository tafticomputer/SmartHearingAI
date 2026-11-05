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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var toggle: Button
    private lateinit var status: TextView
    private lateinit var latency: TextView
    private lateinit var env: TextView
    private lateinit var inMeter: ProgressBar
    private lateinit var outMeter: ProgressBar
    private lateinit var autoSwitch: Switch

    private val seeks = arrayOfNulls<SeekBar>(AudioEngine.PARAM_COUNT)
    private val labels = arrayOfNulls<TextView>(AudioEngine.PARAM_COUNT)
    private val touching = BooleanArray(AudioEngine.PARAM_COUNT)

    private val titles = arrayOf(
        "کنترل نویز (حذف خش‌خش)",
        "تقویت گفتار",
        "حذف صدای باد",
        "بم",
        "زیر",
        "کنترل صدا (بلندی خروجی)"
    )

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

        // ---- حالت خودکار ----
        autoSwitch = Switch(this).apply {
            text = "تنظیم خودکار با هوش مصنوعی"
            textSize = 16f
            isChecked = AudioEngine.autoMode
            setPadding(0, dp(20), 0, dp(4))
            setOnCheckedChangeListener { _, checked -> AudioEngine.autoMode = checked }
        }
        root.addView(autoSwitch)

        env = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#0F766E"))
        }
        root.addView(env)

        root.addView(TextView(this).apply {
            text = "در حالت خودکار، برنامه با تحلیل صدای محیط، نوارها را خودش تنظیم می‌کند. " +
                "هر نواری را که خودتان تکان دهید قفل می‌شود و دیگر خودکار تغییرش نمی‌دهد؛ " +
                "برای برگرداندن آن به حالت خودکار روی نام نوار بزنید."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(4))
        })

        root.addView(Button(this).apply {
            text = "بازگشت همه به حالت خودکار"
            setOnClickListener {
                AudioEngine.unlockAll()
                autoSwitch.isChecked = true
            }
        })

        for (i in 0 until AudioEngine.PARAM_COUNT) addSlider(root, i)

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

    private fun addSlider(parent: LinearLayout, idx: Int) {
        val lbl = label(titles[idx])
        lbl.setOnClickListener {
            // برگرداندن این نوار به حالت خودکار
            AudioEngine.setLocked(idx, false)
        }
        labels[idx] = lbl
        parent.addView(lbl)

        val sb = SeekBar(this).apply {
            max = 100
            progress = (AudioEngine.getParam(idx) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    AudioEngine.setParam(idx, p / 100f)
                    if (AudioEngine.autoMode) AudioEngine.setLocked(idx, true)
                }
                override fun onStartTrackingTouch(s: SeekBar?) { touching[idx] = true }
                override fun onStopTrackingTouch(s: SeekBar?) { touching[idx] = false }
            })
        }
        seeks[idx] = sb
        parent.addView(sb)
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

        if (autoSwitch.isChecked != AudioEngine.autoMode) autoSwitch.isChecked = AudioEngine.autoMode
        env.text = if (AudioEngine.autoMode && on) AudioEngine.envText else ""

        val auto = AudioEngine.autoMode
        for (i in 0 until AudioEngine.PARAM_COUNT) {
            val locked = AudioEngine.isLocked(i)
            // در حالت خودکار، نوارهای قفل‌نشده همراه مقدار انتخابی برنامه حرکت می‌کنند
            if (auto && !locked && !touching[i]) {
                seeks[i]?.progress = (AudioEngine.getParam(i) * 100).toInt()
            }
            labels[i]?.text = titles[i] + when {
                !auto -> ""
                locked -> "   🔒 دستی"
                else -> "   ✨ خودکار"
            }
        }
    }

    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
