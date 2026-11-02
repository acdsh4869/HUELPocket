package com.huelpocket.app.ui.life

import android.content.Context

/**
 * 每种洗衣模式的默认时长（分钟）+ 用户自定义值。
 *
 * 为什么不用官方的 remainTime：实测 `goods/normal/details` 在机器工作时 remainTime 恒为 0，
 * 订单接口又没有 endTime/payTime，只剩 markMinutes（购买分钟数，且桶自洁给的是 5 分钟）。
 * 所以倒计时改成「按选择的模式走本地时钟」，默认值按官方套餐标称时长，用户可逐项改。
 */
internal object WashPlanTimes {

    /** 官方套餐实测到的模式（goods/normal/skus） */
    val defaults: List<Pair<String, Int>> = listOf(
        "桶自洁" to 6,
        "快洗" to 25,
        "标准洗" to 35,
        "大物洗" to 40,
        "单脱" to 10,
        "单洗" to 20,
        "烘干" to 45,
    )

    private const val PREFS = "wash_plan_times"
    private const val FALLBACK = 30

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 某模式的分钟数：用户改过 > 官方套餐标称（sku.unit / 订单 markMinutes）> 内置默认 > 30。
     */
    fun minutesOf(context: Context, mode: String, officialMinutes: Int = 0): Int {
        val saved = prefs(context).getInt(key(mode), 0)
        if (saved > 0) return saved
        if (officialMinutes > 0) return officialMinutes
        return defaults.firstOrNull { it.first == mode }?.second ?: FALLBACK
    }

    fun save(context: Context, mode: String, minutes: Int) {
        if (mode.isBlank() || minutes !in 1..180) return
        prefs(context).edit().putInt(key(mode), minutes).apply()
    }

    private fun key(mode: String) = "m_" + mode.take(20)
}

/**
 * 本机洗衣计时（扫码后自己开的倒计时，不依赖官方订单）。
 *
 * 与官方订单互不干扰：官方有单时也能显示，这里只是「我知道我什么时候开始洗的」。
 */
data class LocalWash(
    val id: Long,
    val machineName: String,
    val mode: String,
    val minutes: Int,
    val startAt: Long,
    /** 官方订单号：同一订单全生命周期只建一条计时器（去重键） */
    val orderNo: String = "",
    /** 设备状态异常（remainTime=0 但 code=2），不再重建计时器，UI 显示异常文案 */
    val abnormal: Boolean = false,
) {
    fun endAt(): Long = startAt + minutes * 60_000L
    fun remainSeconds(now: Long): Int = ((endAt() - now) / 1000).toInt().coerceAtLeast(0)
}

internal object WashTimerStore {
    private const val PREFS = "wash_timers"
    private const val KEY = "list"

    // 行格式：id|machine|mode|minutes|startAt|orderNo|abnormal
    fun read(context: Context): List<LocalWash> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        val list = raw.split("\n").mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 5) return@mapNotNull null
            runCatching {
                LocalWash(
                    id = p[0].toLong(),
                    machineName = p[1],
                    mode = p[2],
                    minutes = p[3].toInt(),
                    startAt = p[4].toLong(),
                    orderNo = p.getOrNull(5).orEmpty(),
                    abnormal = p.getOrNull(6) == "1",
                )
            }.getOrNull()
        }
        return dedupeByOrder(list)
    }

    /**
     * 迁移/清理：同一订单只保留一条（取 startAt 最新的），
     * 没有订单号的旧数据按「机器+模式+开始时间」合并。
     */
    private fun dedupeByOrder(list: List<LocalWash>): List<LocalWash> {
        val byKey = LinkedHashMap<String, LocalWash>()
        list.sortedBy { it.startAt }.forEach { w ->
            val key = if (w.orderNo.isNotBlank()) "o:" + w.orderNo
            else "m:" + w.machineName + "|" + w.mode + "|" + (w.startAt / 60_000L)
            byKey[key] = w
        }
        return byKey.values.toList()
    }

    fun write(context: Context, list: List<LocalWash>) {
        val raw = dedupeByOrder(list).joinToString("\n") { w ->
            listOf(
                w.id, w.machineName, w.mode, w.minutes, w.startAt, w.orderNo,
                if (w.abnormal) "1" else "0",
            ).joinToString("|")
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }

    /** 这个订单是否处理过（查订单台账，唯一判据；台账不随计时器删除） */
    fun hasOrder(context: Context, orderNo: String): Boolean =
        OrderLedger.stateOf(context, orderNo) != null

    /** 新开一条；同一订单/同一台机器的旧记录先清掉 */
    fun start(
        context: Context,
        machineName: String,
        mode: String,
        minutes: Int,
        startAt: Long = System.currentTimeMillis(),
        orderNo: String = "",
    ): LocalWash {
        val now = System.currentTimeMillis()
        val keep = read(context).filter {
            it.orderNo != orderNo && it.machineName != machineName && it.endAt() > now ||
                it.abnormal   // 异常态保留，等机器恢复空闲再清
        }
        val item = LocalWash(
            id = now,
            machineName = machineName,
            mode = mode,
            minutes = minutes,
            startAt = if (startAt > 0) startAt else now,
            orderNo = orderNo,
        )
        write(context, keep + item)
        return item
    }

    /** 用官方 remainTime 校准本地开始时间（显示层以官方为准） */
    fun calibrate(context: Context, orderNo: String, remainSeconds: Int) {
        if (orderNo.isBlank() || remainSeconds <= 0) return
        val list = read(context).map { w ->
            if (w.orderNo == orderNo && !w.abnormal) {
                w.copy(startAt = System.currentTimeMillis() - (w.minutes * 60_000L - remainSeconds * 1000L))
            } else {
                w
            }
        }
        write(context, list)
    }

    fun markAbnormal(context: Context, orderNo: String, abnormal: Boolean = true) {
        val list = read(context).map { if (it.orderNo == orderNo) it.copy(abnormal = abnormal) else it }
        write(context, list)
    }

    /** 启动迁移：把现存计时器的 orderNo 补写进订单台账（不重置计时器本身） */
    fun migrateToLedger(context: Context) {
        read(context).forEach { w ->
            if (w.orderNo.isNotBlank() && OrderLedger.stateOf(context, w.orderNo) == null) {
                OrderLedger.mark(context, w.orderNo, if (w.abnormal) OrderLedger.ABNORMAL else OrderLedger.STARTED)
            }
        }
    }

    fun remove(context: Context, id: Long) {
        write(context, read(context).filterNot { it.id == id })
    }

    /** 清掉已经到点的 */
    /**
     * 只清理计时器显示记录；订单台账（OrderLedger）不受影响。
     *
     * `abnormal` 必须豁免：标记异常的前提就是 `now >= endAt`，若照常删除，
     * 异常行活不过标记它的同一个刷新周期，「设备状态异常·待官方处理」永远显示不出来。
     * 异常行的移除交给 `reconcileTimers()` 第三分支（机器恢复空闲时）。
     */
    fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        val alive = read(context).filter { it.endAt() > now || it.abnormal }
        if (alive.size != read(context).size) write(context, alive)
    }
}

/**
 * 订单台账：`orderNo -> state(started/done/abnormal) + 时间戳`。
 *
 * 为什么单独存：计时器到点会被 `WashTimerStore.prune()` 删掉，若把「这个订单处理过没有」
 * 记在计时器上，prune 之后判重就会失效，导致同一笔卡住的订单反复重建计时器（实测 17 号）。
 * 惰性过期：读写时顺手过滤 24 小时以上的条目，不额外加定时任务。
 */
internal object OrderLedger {
    const val STARTED = "started"
    const val DONE = "done"
    const val ABNORMAL = "abnormal"

    private const val PREFS = "handled_orders"
    private const val KEY = "list"
    private const val TTL = 24 * 60 * 60_000L

    private fun readAll(context: Context): MutableMap<String, Pair<String, Long>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return mutableMapOf()
        val now = System.currentTimeMillis()
        val map = mutableMapOf<String, Pair<String, Long>>()
        raw.split("\n").forEach { line ->
            val p = line.split("|")
            if (p.size < 3) return@forEach
            val ts = p[2].toLongOrNull() ?: return@forEach
            if (now - ts > TTL) return@forEach   // 惰性清理过期项
            map[p[0]] = p[1] to ts
        }
        return map
    }

    private fun writeAll(context: Context, map: Map<String, Pair<String, Long>>) {
        val raw = map.entries.joinToString("\n") { (k, v) -> k + "|" + v.first + "|" + v.second }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, raw).apply()
    }

    fun stateOf(context: Context, orderNo: String): String? {
        if (orderNo.isBlank()) return null
        return readAll(context)[orderNo]?.first
    }

    fun mark(context: Context, orderNo: String, state: String) {
        if (orderNo.isBlank()) return
        val map = readAll(context)
        map[orderNo] = state to System.currentTimeMillis()
        writeAll(context, map)
    }

    /** dump 用：返回 orderNo=state(时间) 的可读列表 */
    fun dump(context: Context): List<String> =
        readAll(context).map { (k, v) -> k + "=" + v.first + "(" + v.second + ")" }
}
