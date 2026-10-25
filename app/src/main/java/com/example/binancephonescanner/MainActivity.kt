package com.example.binancephonescanner

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var logs: LinearLayout
    private lateinit var monitorList: LinearLayout
    private var monitors = mutableListOf<MonitorConfig>()
    private val cyan = Color.rgb(100, 224, 255)
    private val white = Color.rgb(239, 246, 255)
    private val muted = Color.rgb(151, 174, 198)
    private val bg = Color.rgb(7, 13, 28)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitors = ConfigStore.load(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        buildUi()
        val filter = IntentFilter("com.example.binancephonescanner.STATUS")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(statusReceiver, filter)
        refreshMonitors()
        refreshStatus()
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra("status") ?: "감시 상태 확인 중"
            progress.progress = intent?.getIntExtra("progress", 0) ?: 0
            progressText.text = intent?.getStringExtra("progressText") ?: "대기 중"
            intent?.getStringExtra("log")?.let { addLog(it) }
            intent?.getStringExtra("matches")?.takeIf { it.isNotBlank() }?.let { addLog("조건 일치: $it") }
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 22, 18, 30) }
        scroll.addView(root)
        setContentView(scroll)

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply { text = "GLASS SCANNER"; textSize = 21f; setTextColor(white); typeface = Typeface.DEFAULT_BOLD; letterSpacing = .08f }
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("조건 설정", true) { showSettings() })
        root.addView(top)
        root.addView(text("BINANCE MARKET MONITOR · 공개 시장 데이터", 12f, muted).apply { setPadding(2, 7, 0, 18) })

        card(root) {
            addView(text("MONITOR STATUS", 11f, cyan))
            status = text("감시 중지", 22f, white).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, 7, 0, 12) }
            addView(status)
            progress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = android.content.res.ColorStateList.valueOf(cyan); progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(28, 43, 66)) }
            addView(progress, LinearLayout.LayoutParams(-1, 8))
            progressText = text("첫 검사를 시작할 준비가 됐어요", 12f, muted).apply { setPadding(0, 8, 0, 0) }
            addView(progressText)
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 14, 0, 14) }
        buttons.addView(button("감시 시작", true) { startMonitoring() }, LinearLayout.LayoutParams(0, 52, 1f).apply { marginEnd = 6 })
        buttons.addView(button("감시 중지", false) { stopMonitoring() }, LinearLayout.LayoutParams(0, 52, 1f).apply { marginStart = 6 })
        root.addView(buttons)

        root.addView(text("감시 목록", 18f, white).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(2, 8, 0, 10) })
        monitorList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(monitorList)
        root.addView(text("최근 검사 기록", 18f, white).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(2, 18, 0, 10) })
        logs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(logs)
    }

    private fun refreshMonitors() {
        monitorList.removeAllViews()
        monitors.forEach { m ->
            card(monitorList) {
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                info.addView(text(m.name, 16f, white).apply { typeface = Typeface.DEFAULT_BOLD })
                info.addView(text("${marketLabel(m.market)} · 매시 ${"%02d".format(m.minute)}분 · 조건 ${m.conditions.size}개", 12f, muted).apply { setPadding(0, 5, 0, 0) })
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button("편집", false) { editMonitor(m) })
                row.addView(button("삭제", false) { if (monitors.size > 1) { monitors.remove(m); saveRefresh() } else toast("감시는 최소 1개가 필요합니다.") })
                addView(row)
            }
        }
        monitorList.addView(button("＋ 감시 추가", true) {
            val n = monitors.size + 1
            val m = MonitorConfig(System.currentTimeMillis(), "감시 $n")
            monitors.add(m); saveRefresh(); editMonitor(m)
        })
    }

    private fun showSettings() {
        val choices = arrayOf("감시 1 설정", "감시 추가")
        AlertDialog.Builder(this).setTitle("조건 설정").setItems(choices) { _, which ->
            if (which == 0) editMonitor(monitors.first()) else {
                val m = MonitorConfig(System.currentTimeMillis(), "감시 ${monitors.size + 1}")
                monitors.add(m); saveRefresh(); editMonitor(m)
            }
        }.setNegativeButton("닫기", null).show()
    }

    private fun editMonitor(m: MonitorConfig) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 0) }
        val name = EditText(this).apply { setText(m.name); hint = "감시 이름"; setTextColor(white); setHintTextColor(muted) }
        box.addView(label("감시 이름")); box.addView(name)
        val market = Spinner(this).apply { adapter = adapter(arrayOf("USDT-M 선물", "현물", "둘 다")); setSelection(when (m.market) { "SPOT" -> 1; "BOTH" -> 2; else -> 0 }) }
        box.addView(label("시장")); box.addView(market)
        val minute = Spinner(this).apply {
            adapter = adapter(arrayOf("정각 (:00)", "매 10분 (:10)", "매 15분 (:15)", "매 30분 (:30)", "매 50분 (:50)", "매 59분 (:59)", "사용자 지정 분"))
            setSelection(when (m.minute) { 0 -> 0; 10 -> 1; 15 -> 2; 30 -> 3; 50 -> 4; 59 -> 5; else -> 6 })
        }
        val customMinute = EditText(this).apply { hint = "사용자 지정 분 (0~59)"; inputType = 2; setText(m.minute.toString()); setTextColor(white); setHintTextColor(muted) }
        box.addView(label("검사 실행 시점")); box.addView(minute); box.addView(customMinute)
        val condHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        condHeader.addView(label("조건 목록"), LinearLayout.LayoutParams(0, -2, 1f))
        condHeader.addView(button("＋ 조건 추가", true) { addConditionDialog(m) })
        box.addView(condHeader)
        val condSummary = TextView(this).apply { setTextColor(muted); textSize = 12f; setPadding(0, 5, 0, 10) }
        fun updateSummary() { condSummary.text = m.conditions.mapIndexed { i, c -> "${i + 1}. ${conditionLabel(c)} · ${c.timeframe}" }.joinToString("\n") }
        updateSummary(); box.addView(condSummary)
        AlertDialog.Builder(this).setTitle("감시 설정").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장") { _, _ ->
                m.name = name.text.toString().trim().ifBlank { "감시 ${monitors.indexOf(m) + 1}" }
                m.market = when (market.selectedItemPosition) { 1 -> "SPOT"; 2 -> "BOTH"; else -> "FUTURES" }
                m.minute = when (minute.selectedItemPosition) { 0 -> 0; 1 -> 10; 2 -> 15; 3 -> 30; 4 -> 50; 5 -> 59; else -> customMinute.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 50 }
                saveRefresh()
            }.setNeutralButton("조건 편집") { _, _ -> editConditions(m) }
            .setNegativeButton("취소", null).show()
    }

    private fun editConditions(m: MonitorConfig) {
        val items = m.conditions.mapIndexed { i, c -> "${i + 1}. ${conditionLabel(c)} · ${c.timeframe}" }.toMutableList()
        items.add("＋ 새 조건 추가")
        AlertDialog.Builder(this).setTitle("조건 편집").setItems(items.toTypedArray()) { _, which ->
            if (which == m.conditions.size) addConditionDialog(m)
            else AlertDialog.Builder(this).setTitle(items[which]).setItems(arrayOf("수정", "삭제")) { _, action ->
                if (action == 0) configureCondition(m, m.conditions[which]) else {
                    if (m.conditions.size > 1) m.conditions.removeAt(which) else toast("조건은 최소 1개가 필요합니다.")
                    saveRefresh(); editConditions(m)
                }
            }.show()
        }.setNegativeButton("닫기", null).show()
    }

    private fun addConditionDialog(m: MonitorConfig) {
        val types = arrayOf("거래량 증가", "이동평균선 배열", "이동평균선 비교", "가격 등락률")
        AlertDialog.Builder(this).setTitle("조건 추가").setItems(types) { _, i ->
            val c = ScanCondition(type = arrayOf("volume", "ma_stack", "ma_compare", "price_change")[i])
            m.conditions.add(c); configureCondition(m, c)
        }.show()
    }

    private fun configureCondition(m: MonitorConfig, c: ScanCondition) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 6, 22, 0) }
        fun spinner(title: String, values: Array<String>, selected: Int = 0): Spinner {
            box.addView(label(title))
            return Spinner(this).apply { adapter = adapter(values); setSelection(selected); box.addView(this) }
        }
        val tfValues = arrayOf("1분", "5분", "15분", "1시간", "4시간", "1일")
        val tfCodes = arrayOf("1m", "5m", "15m", "1h", "4h", "1d")
        val tf = spinner("타임프레임", tfValues, tfCodes.indexOf(c.timeframe).coerceAtLeast(0))
        val ma = spinner("이동평균 종류", arrayOf("EMA (지수)", "SMA (단순)"), if (c.maType == "SMA") 1 else 0)
        val mode = spinner("거래량 기준", arrayOf("배율 (예: 4.5배)", "퍼센트 증가 (예: 350%)"), if (c.mode == "percent") 1 else 0)
        val fast = EditText(this).apply { hint = "빠른 이동평균 기간 (기본 10)"; inputType = 2; setText(c.fastPeriod.toString()); setTextColor(white); setHintTextColor(muted) }
        val slow = EditText(this).apply { hint = "느린 이동평균 기간 (기본 20)"; inputType = 2; setText(c.slowPeriod.toString()); setTextColor(white); setHintTextColor(muted) }
        val value = EditText(this).apply { hint = "기준값 (예: 4.5 또는 5)"; inputType = 8194; setText(c.value.toString()); setTextColor(white); setHintTextColor(muted) }
        val second = EditText(this).apply { hint = "보조값 (선택)"; inputType = 8194; setText(c.secondValue.toString()); setTextColor(white); setHintTextColor(muted) }
        box.addView(label("조건 기준값")); box.addView(value)
        box.addView(label("이동평균 기간")); box.addView(fast); box.addView(slow)
        box.addView(label("비교값 / 보조값")); box.addView(second)
        AlertDialog.Builder(this).setTitle(conditionLabel(c)).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("저장") { _, _ ->
                c.timeframe = tfCodes[tf.selectedItemPosition]
                c.maType = if (ma.selectedItemPosition == 1) "SMA" else "EMA"
                c.mode = if (mode.selectedItemPosition == 1) "percent" else "multiple"
                c.value = value.text.toString().toDoubleOrNull() ?: c.value
                c.secondValue = second.text.toString().toDoubleOrNull() ?: 0.0
                c.fastPeriod = fast.text.toString().toIntOrNull()?.coerceIn(2, 200) ?: 10
                c.slowPeriod = slow.text.toString().toIntOrNull()?.coerceIn(2, 200) ?: 20
                saveRefresh()
            }.setNegativeButton("취소", null).show()
    }

    private fun startMonitoring() {
        ConfigStore.save(this, monitors)
        val i = Intent(this, ScannerService::class.java).setAction(ScannerService.ACTION_START)
        ContextCompat.startForegroundService(this, i)
        status.text = "첫 검사 준비 중…"
    }
    private fun stopMonitoring() {
        startService(Intent(this, ScannerService::class.java).setAction(ScannerService.ACTION_STOP))
        status.text = "감시 중지 요청"
    }
    private fun refreshStatus() {
        val prefs = getSharedPreferences("glass_scanner_runtime", 0)
        status.text = prefs.getString("status", "감시 중지") ?: "감시 중지"
        progress.progress = prefs.getInt("progress", 0)
        progressText.text = prefs.getString("progressText", "대기 중") ?: "대기 중"
        prefs.getString("logs", "")?.split("\n")?.filter { it.isNotBlank() }?.takeLast(8)?.forEach { addLog(it) }
    }
    private fun addLog(s: String) {
        if (!::logs.isInitialized) return
        if (logs.childCount > 30) logs.removeViewAt(0)
        logs.addView(text(s, 12f, muted).apply { setPadding(10, 8, 10, 8); background = shape(Color.rgb(15, 27, 47), 12) }, 0)
    }
    private fun saveRefresh() { ConfigStore.save(this, monitors); refreshMonitors() }
    private fun conditionLabel(c: ScanCondition) = when (c.type) {
        "volume" -> "거래량 증가 ${c.value}${if (c.mode == "percent") "%" else "배"}"
        "ma_stack" -> "${c.maType} 배열"
        "ma_compare" -> "${c.maType} 비교"
        else -> "가격 등락률 ${c.value}%"
    }
    private fun marketLabel(m: String) = when (m) { "SPOT" -> "현물"; "BOTH" -> "현물+선물"; else -> "USDT-M 선물" }
    private fun label(s: String) = text(s, 12f, muted).apply { setPadding(0, 9, 0, 3) }
    private fun adapter(items: Array<String>) = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    private fun text(s: String, size: Float, color: Int) = TextView(this).apply { text = s; textSize = size; setTextColor(color) }
    private fun button(s: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = s; textSize = 12f; isAllCaps = false; setTextColor(if (primary) Color.rgb(5, 20, 35) else white)
        background = shape(if (primary) cyan else Color.rgb(24, 38, 61), 15); setPadding(14, 0, 14, 0); setOnClickListener { action() }
    }
    private fun card(parent: LinearLayout, content: LinearLayout.() -> Unit) {
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16); background = shape(Color.rgb(16, 28, 49), 22); content() }
        parent.addView(l, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
    }
    private fun shape(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat(); setStroke(1, Color.rgb(40, 72, 101)) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
