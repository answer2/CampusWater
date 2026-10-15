package dev.answer.campuswater.data

import dev.answer.campuswater.api.DeviceRecord
import dev.answer.campuswater.api.Settlement

/** 连接阶段。 */
enum class EnginePhase {
    /** 还没连过，或已经主动断开。 */
    DISCONNECTED,

    CONNECTING,

    /** 蓝牙链路就绪，可以发指令。 */
    READY,

    CONNECT_FAILED,
}

/** 状态文字的颜色语义，界面据此取主题色。 */
enum class Tone { NEUTRAL, OK, WARN, ERROR }

/** 一单的结算结果（内存态，可能金额还在补查中）。 */
data class SettlementInfo(
    val moneyYuan: Double? = null,
    val preDeductYuan: Double? = null,
    val durationMs: Long = 0,
    val consumeTime: String = "",
    val autoClosed: Boolean = false,
    val source: String = "",
) {
    /** 金额还没出：显示"结算中"，绝不能显示 ¥0.00。 */
    val pending: Boolean get() = moneyYuan == null

    companion object {
        fun of(s: Settlement, autoClosed: Boolean): SettlementInfo = SettlementInfo(
            moneyYuan = s.money,
            preDeductYuan = s.preDeduct,
            durationMs = s.durationMs,
            consumeTime = s.consumeTime ?: "",
            autoClosed = autoClosed,
            source = s.source ?: "",
        )
    }
}

/** 用水会话的界面状态。[WaterEngine] 是唯一写入方。 */
data class WaterUiState(
    val device: DeviceRecord? = null,
    val phase: EnginePhase = EnginePhase.DISCONNECTED,
    val busy: Boolean = false,
    val statusText: String = "尚未连接设备",
    val statusTone: Tone = Tone.NEUTRAL,
    val inUse: Boolean = false,
    val usedByMe: Boolean = false,
    val startedAtMs: Long = 0L,
    val deadlineMs: Long = 0L,
    /** 服务端下发的闲置自动关停时长（秒），0 = 没下发。到点确认后要靠它重新计时。 */
    val autoDisconSec: Int = 0,
    val lastSettlement: SettlementInfo? = null,
) {
    /** 闲置自动关停还剩多少秒；没有下发时长时返回 0。 */
    fun countdownRemainSec(nowMs: Long): Int {
        if (deadlineMs <= 0L) {
            return 0
        }
        return ((deadlineMs - nowMs) / 1000L).coerceAtLeast(0L).toInt()
    }

    val actionLabel: String
        get() = when {
            phase == EnginePhase.CONNECTING -> "正在连接…"
            phase == EnginePhase.DISCONNECTED || phase == EnginePhase.CONNECT_FAILED -> "连接设备"
            inUse -> "结束用水"
            else -> "开始用水"
        }
}

/** 需要提示用户、且可以选择性地用横幅（heads-up 通知 + 界面顶部横幅）的事件。 */
enum class BannerKind { OPEN_FAILED, OCCUPIED, AUTO_CLOSED, CLOSE_FAILED, INFO }

sealed class WaterEvent {
    data class Banner(val kind: BannerKind, val title: String, val message: String) : WaterEvent()

    /** 一单结束（含结算结果，金额可能还在补查）。 */
    data class Finished(val info: SettlementInfo) : WaterEvent()

    /** 倒计时到点：界面弹确认框；App 不在前台时只有通知。 */
    data class CountdownElapsed(val info: SettlementInfo?) : WaterEvent()
}

/** 扫描页状态。 */
data class ScanState(
    val scanning: Boolean = false,
    val message: String = "",
    val devices: List<DeviceRecord> = emptyList(),
)
