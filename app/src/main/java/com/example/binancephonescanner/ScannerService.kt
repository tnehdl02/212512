package com.example.binancephonescanner

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class ScannerService : Service() {
    companion object {
        const val ACTION_START = "scanner.START"
        const val ACTION_STOP = "scanner.STOP"
        const val ACTION_SCAN = "scanner.SCAN"
        private const val CHANNEL = "glass_scanner_channel"
        private const val NOTIFICATION_ID = 771
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Glass Scanner", NotificationManager.IMPORTANCE_LOW).apply { description = "감시 상태 및 조건 일치 알림" })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { running.set(false); cancelAlarm(); releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); broadcast("감시 중지", 0, "대기 중"); return START_NOT_STICKY }
            ACTION_SCAN -> {
                if (!running.get()) {
                    running.set(true)
                    startForeground(NOTIFICATION_ID, notification("예약 검사 시작"))
                    acquireWakeLock()
                    executor.execute { scanAll() }
                } else {
                    executor.execute { scanAll() }
                }
            }
            else -> {
                if (!running.get()) {
                    running.set(true)
                    startForeground(NOTIFICATION_ID, notification("첫 검사 시작"))
                    acquireWakeLock()
                    executor.execute { scanAll() }
                    scheduleNext()
                }
            }
        }
        return START_STICKY
    }

    @Volatile private var scanInProgress = false

    @Synchronized
    private fun scanAll() {
        if (!running.get() || scanInProgress) return
        scanInProgress = true
        val configs = ConfigStore.load(this).filter { it.enabled }
        if (configs.isEmpty()) { broadcast("활성 감시 없음", 0, "조건 설정에서 감시를 활성화하세요"); return }
        val found = mutableListOf<String>()
        var total = 0
        var checked = 0
        val started = stamp()
        appendLog("$started | 검사 시작 | 감시 ${configs.size}개")
        try {
            for (config in configs) {
                if (!running.get()) break
                val markets = when (config.market) { "SPOT" -> listOf("SPOT"); "BOTH" -> listOf("SPOT", "FUTURES"); else -> listOf("FUTURES") }
                for (market in markets) {
                    val symbols = getSymbols(market)
                    total += symbols.size
                    broadcast("감시 중 · ${config.name}", 0, "종목 목록 조회 · ${symbols.size}개")
                    for ((index, symbol) in symbols.withIndex()) {
                        if (!running.get()) break
                        try {
                            val ok = config.conditions.all { testCondition(market, symbol, it) }
                            if (ok) found.add("${config.name} · ${marketLabel(market)} · $symbol")
                        } catch (_: Exception) { }
                        checked++
                        if (checked % 5 == 0 || index == symbols.lastIndex) {
                            val p = if (total > 0) (checked * 100 / maxOf(total, checked)).coerceIn(0, 100) else 0
                            broadcast("감시 중 · ${config.name}", p, "$checked / ${maxOf(total, checked)} · $p%")
                        }
                    }
                }
            }
            val message = if (found.isEmpty()) "일치 종목 없음" else "조건 일치 ${found.size}개: ${found.take(20).joinToString(", ")}"
            appendLog("${stamp()} | 검사 완료 | $checked개 검사 · ${found.size}개 일치")
            broadcast(if (running.get()) "감시 실행 중" else "감시 중지", 100, "$checked개 검사 완료 · ${found.size}개 일치", message)
            if (found.isNotEmpty()) notifyMatches(found)
        } catch (e: Exception) {
            appendLog("${stamp()} | 검사 실패 | ${e.message ?: "네트워크 오류"}")
            broadcast("검사 실패", 0, e.message ?: "네트워크 오류")
        } finally {
            scanInProgress = false
            if (running.get()) scheduleNext()
        }
    }

    private fun testCondition(market: String, symbol: String, c: ScanCondition): Boolean {
        val klines = getKlines(market, symbol, c.timeframe, maxOf(c.slowPeriod + 3, 30))
        if (klines.length() < 3) return false
        val current = klines.getJSONArray(klines.length() - 1)
        val prev = klines.getJSONArray(klines.length() - 2)
        val open = current.getDouble(1)
        val close = current.getDouble(4)
        val quoteVol = current.getDouble(7)
        val prevVol = prev.getDouble(7)
        val pct = if (open != 0.0) (close - open) / open * 100.0 else 0.0
        return when (c.type) {
            "volume" -> {
                val ratio = if (prevVol > 0.0) quoteVol / prevVol else 0.0
                if (c.mode == "percent") (ratio - 1.0) * 100.0 >= c.value else ratio >= c.value
            }
            "price_change" -> pct >= c.value
            "ma_stack" -> {
                val closes = (0 until klines.length()).map { klines.getJSONArray(it).getDouble(4) }
                val fast = average(closes, c.fastPeriod, c.maType)
                val slow = average(closes, c.slowPeriod, c.maType)
                fast > slow
            }
            "ma_compare" -> {
                val closes = (0 until klines.length()).map { klines.getJSONArray(it).getDouble(4) }
                val fast = average(closes, c.fastPeriod, c.maType)
                val slow = average(closes, c.slowPeriod, c.maType)
                fast > slow
            }
            else -> false
        }
    }

    private fun average(values: List<Double>, period: Int, type: String): Double {
        if (values.size < period) return 0.0
        val slice = values.takeLast(period)
        if (type == "SMA") return slice.average()
        var ema = slice.first()
        val alpha = 2.0 / (period + 1.0)
        for (v in slice.drop(1)) ema = v * alpha + ema * (1 - alpha)
        return ema
    }

    private fun getSymbols(market: String): List<String> {
        val url = if (market == "SPOT") "https://api.binance.com/api/v3/exchangeInfo" else "https://fapi.binance.com/fapi/v1/exchangeInfo"
        val obj = JSONObject(request(url))
        val arr = obj.getJSONArray("symbols")
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val status = s.optString("status")
            val quote = s.optString("quoteAsset")
            val symbol = s.optString("symbol")
            if (status == "TRADING" && quote == "USDT" && (market == "SPOT" || s.optString("contractType") == "PERPETUAL")) result.add(symbol)
        }
        return result
    }

    private fun getKlines(market: String, symbol: String, tf: String, limit: Int): JSONArray {
        val base = if (market == "SPOT") "https://api.binance.com/api/v3/klines" else "https://fapi.binance.com/fapi/v1/klines"
        val data = JSONArray(request("$base?symbol=$symbol&interval=$tf&limit=${limit.coerceIn(10, 200)}"))
        val result = JSONArray()
        for (i in 0 until data.length()) {
            val a = data.getJSONArray(i)
            result.put(JSONObject().apply {
                put("openTime", a.getLong(0)); put("open", a.getDouble(1)); put("high", a.getDouble(2))
                put("low", a.getDouble(3)); put("close", a.getDouble(4)); put("volume", a.getDouble(5))
                put("closeTime", a.getLong(6)); put("quoteVolume", a.getDouble(7))
            })
        }
        // Normalize kline object indices used by testCondition.
        val normalized = JSONArray()
        for (i in 0 until result.length()) {
            val o = result.getJSONObject(i)
            normalized.put(JSONArray().put(o.getLong("openTime")).put(o.getDouble("open")).put(o.getDouble("high"))
                .put(o.getDouble("low")).put(o.getDouble("close")).put(o.getDouble("volume"))
                .put(o.getLong("closeTime")).put(o.getDouble("quoteVolume")))
        }
        return normalized
    }

    private fun request(urlString: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection)
        conn.connectTimeout = 8000; conn.readTimeout = 10000; conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "GlassScanner/2.0")
        return try {
            if (conn.responseCode !in 200..299) throw Exception("Binance HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun scheduleNext() {
        if (!running.get()) return
        val configs = ConfigStore.load(this).filter { it.enabled }
        if (configs.isEmpty()) return
        val now = Calendar.getInstance()
        val minutes = configs.map { it.minute }.distinct().sorted()
        val next = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            var target: Int? = minutes.firstOrNull { it > now.get(Calendar.MINUTE) }
            if (target == null) { add(Calendar.HOUR_OF_DAY, 1); target = minutes.firstOrNull() ?: 0 }
            set(Calendar.MINUTE, target!!)
        }
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(this, 9001, Intent(this, ScanAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
            } else am.setExact(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
        } catch (_: Exception) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi) }
    }

    private fun cancelAlarm() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(this, 9001, Intent(this, ScanAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlassScanner:ScanLock").apply { setReferenceCounted(false); acquire(10 * 60 * 1000L) }
        } catch (_: Exception) {}
    }
    private fun releaseWakeLock() { try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {} }

    private fun broadcast(status: String, progress: Int, progressText: String, matches: String = "") {
        getSharedPreferences("glass_scanner_runtime", 0).edit().putString("status", status).putInt("progress", progress).putString("progressText", progressText).apply()
        sendBroadcast(Intent("com.example.binancephonescanner.STATUS").setPackage(packageName).putExtra("status", status).putExtra("progress", progress).putExtra("progressText", progressText).putExtra("log", if (progress == 0) "${stamp()} | $status | $progressText" else "").putExtra("matches", matches))
        updateNotification("$status · $progressText")
    }
    private fun appendLog(s: String) {
        val p = getSharedPreferences("glass_scanner_runtime", 0)
        val old = p.getString("logs", "") ?: ""
        p.edit().putString("logs", (old + "\n" + s).split("\n").takeLast(30).joinToString("\n")).apply()
    }
    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, ScannerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("Glass Scanner").setContentText(text).setContentIntent(open).setOngoing(true).addAction(0, "중지", stop).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }
    private fun updateNotification(text: String) { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification(text)) }
    private fun notifyMatches(found: List<String>) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("glass_match_channel", "조건 일치 알림", NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(this, "glass_match_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("조건 일치 종목 ${found.size}개")
            .setContentText(found.take(3).joinToString(", ")).setStyle(NotificationCompat.BigTextStyle().bigText(found.take(15).joinToString("\n")))
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        nm.notify((System.currentTimeMillis() % 100000).toInt(), n)
    }
    private fun stamp() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date())
    private fun marketLabel(m: String) = if (m == "SPOT") "현물" else "선물"
    override fun onBind(intent: Intent?): IBinder? = null
}
