package com.example.binancephonescanner

import org.json.JSONArray
import org.json.JSONObject

data class ScanCondition(
    var type: String = "volume",
    var timeframe: String = "1h",
    var operator: String = "gte",
    var value: Double = 4.5,
    var secondValue: Double = 0.0,
    var maType: String = "EMA",
    var fastPeriod: Int = 10,
    var slowPeriod: Int = 20,
    var mode: String = "multiple"
) {
    fun toJson() = JSONObject().apply {
        put("type", type); put("timeframe", timeframe); put("operator", operator)
        put("value", value); put("secondValue", secondValue); put("maType", maType)
        put("fastPeriod", fastPeriod); put("slowPeriod", slowPeriod); put("mode", mode)
    }
    companion object {
        fun fromJson(o: JSONObject) = ScanCondition(
            type = o.optString("type", "volume"),
            timeframe = o.optString("timeframe", "1h"),
            operator = o.optString("operator", "gte"),
            value = o.optDouble("value", 4.5),
            secondValue = o.optDouble("secondValue", 0.0),
            maType = o.optString("maType", "EMA"),
            fastPeriod = o.optInt("fastPeriod", 10),
            slowPeriod = o.optInt("slowPeriod", 20),
            mode = o.optString("mode", "multiple")
        )
    }
}

data class MonitorConfig(
    val id: Long,
    var name: String,
    var market: String = "FUTURES",
    var minute: Int = 50,
    var enabled: Boolean = true,
    var conditions: MutableList<ScanCondition> = mutableListOf(ScanCondition())
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("market", market); put("minute", minute); put("enabled", enabled)
        put("conditions", JSONArray().also { a -> conditions.forEach { a.put(it.toJson()) } })
    }
    companion object {
        fun fromJson(o: JSONObject) = MonitorConfig(
            id = o.optLong("id", System.currentTimeMillis()),
            name = o.optString("name", "감시"),
            market = o.optString("market", "FUTURES"),
            minute = o.optInt("minute", 50),
            enabled = o.optBoolean("enabled", true),
            conditions = mutableListOf<ScanCondition>().apply {
                val a = o.optJSONArray("conditions") ?: JSONArray()
                for (i in 0 until a.length()) add(ScanCondition.fromJson(a.getJSONObject(i)))
                if (isEmpty()) add(ScanCondition())
            }
        )
    }
}

object ConfigStore {
    private const val PREF = "glass_scanner"
    private const val KEY = "monitors"
    fun load(ctx: android.content.Context): MutableList<MonitorConfig> {
        val raw = ctx.getSharedPreferences(PREF, 0).getString(KEY, null)
        if (raw == null) return mutableListOf(MonitorConfig(1, "감시 1"))
        return try {
            val a = JSONArray(raw)
            mutableListOf<MonitorConfig>().apply { for (i in 0 until a.length()) add(MonitorConfig.fromJson(a.getJSONObject(i))) }
        } catch (_: Exception) { mutableListOf(MonitorConfig(1, "감시 1")) }
    }
    fun save(ctx: android.content.Context, list: List<MonitorConfig>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        ctx.getSharedPreferences(PREF, 0).edit().putString(KEY, a.toString()).apply()
    }
}
