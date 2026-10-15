package dev.answer.campuswater.data

import android.content.Context
import android.content.SharedPreferences
import dev.answer.campuswater.api.DeviceRecord
import org.json.JSONObject

/**
 * 「这一次用水的现场」。
 *
 * <p>存在的意义只有一个：进程被杀（划掉最近任务、系统回收）之后，服务冷启动时
 * 还能知道"我正在用哪台设备、什么时候开始的、什么时候该自动关停"，
 * 从而把这一单结掉，而不是让服务端的预扣款一直挂着。
 */
object WaterStore {

    private const val PREF = "campuswater_water_session"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        }
    }

    /** 开始用水：写入设备、开始时刻与自动关停截止时间。 */
    fun markActive(device: DeviceRecord, startedAtMs: Long, deadlineMs: Long) {
        val e = prefs.edit()
        e.putString(KEY_DEVICE, deviceToJson(device))
        e.putLong("startedAt", startedAtMs)
        e.putLong("deadlineAt", if (deadlineMs > 0) deadlineMs else 0L)
        e.putBoolean("active", true)
        e.remove("endedAt")
        e.apply()
    }

    /** 只更新截止时间（恢复场景：设备还在用，但本地没有倒计时基准）。 */
    fun updateDeadline(deadlineMs: Long) {
        prefs.edit().putLong("deadlineAt", deadlineMs).apply()
    }

    fun markFinished(endedAtMs: Long) {
        prefs.edit()
            .putBoolean("active", false)
            .putLong("endedAt", endedAtMs)
            .apply()
    }

    val isActive: Boolean get() = prefs.getBoolean("active", false)

    val startedAtMs: Long get() = prefs.getLong("startedAt", 0L)

    /** 自动关停截止时刻；{@code 0} 表示服务端没下发时长。 */
    val deadlineMs: Long get() = prefs.getLong("deadlineAt", 0L)

    val endedAtMs: Long get() = prefs.getLong("endedAt", 0L)

    fun device(): DeviceRecord? = jsonToDevice(prefs.getString(KEY_DEVICE, null))

    fun clearDevice() {
        prefs.edit().remove(KEY_DEVICE).apply()
    }

    /** 本次会话彻底结束（结算也记完了）。 */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // -------------------------------------------------------------- 最近一笔消费

    /**
     * 记下最近一单的消费结果。
     *
     * <p>结算补查是异步的，可能在"结束"通知发出之后才拿到金额，
     * 所以既要在内存里更新，也要落盘，界面上才能显示"上次消费"。
     */
    fun recordSettlement(moneyYuan: Double, durationMs: Long, consumeTime: String) {
        prefs.edit()
            .putString("lastMoney", moneyYuan.toString())
            .putLong("lastDurationMs", durationMs)
            .putString("lastConsumeTime", consumeTime)
            .putLong("lastRecordedAt", System.currentTimeMillis())
            .apply()
    }

    val lastMoney: Double?
        get() = prefs.getString("lastMoney", null)?.toDoubleOrNull()

    val lastDurationMs: Long get() = prefs.getLong("lastDurationMs", 0L)

    val lastConsumeTime: String get() = prefs.getString("lastConsumeTime", "") ?: ""

    // -------------------------------------------------------------- JSON

    private const val KEY_DEVICE = "device"

    private fun deviceToJson(d: DeviceRecord): String = JSONObject()
        .put("devId", d.devId)
        .put("bigTypeId", d.bigTypeId)
        .put("smallTypeId", d.smallTypeId)
        .put("projectId", d.projectId)
        .put("deviceName", d.deviceName)
        .put("communicationTypeId", d.communicationTypeId)
        .put("bleAddress", d.bleAddress)
        .put("advMac", d.advMac)
        .toString()

    private fun jsonToDevice(raw: String?): DeviceRecord? {
        if (raw.isNullOrEmpty()) {
            return null
        }
        return try {
            val o = JSONObject(raw)
            DeviceRecord().apply {
                devId = o.optString("devId")
                bigTypeId = o.optInt("bigTypeId")
                smallTypeId = o.optInt("smallTypeId")
                projectId = o.optString("projectId")
                deviceName = o.optString("deviceName")
                communicationTypeId = o.optInt("communicationTypeId")
                bleAddress = o.optString("bleAddress")
                advMac = o.optString("advMac")
            }
        } catch (e: Exception) {
            null
        }
    }
}
