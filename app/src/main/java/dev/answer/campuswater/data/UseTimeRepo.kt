package dev.answer.campuswater.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.answer.campuswater.api.ApiConfig
import dev.answer.campuswater.api.UseTimeSlot
import dev.answer.campuswater.api.WaterApi
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 使用时段（模块供水时间）。
 *
 * <p>原来在 Fragment 和 Activity 里各写了一份完全一样的加载代码，这里收成一处：
 * 同一 moduleId 只查一次，结果既进内存也走 [UseTimeSlot] 的缓存。
 */
object UseTimeRepo {

    data class State(
        val loading: Boolean = false,
        val slots: List<UseTimeSlot> = emptyList(),
        val error: String? = null,
        val moduleId: Int = 0,
    ) {
        val summary: String
            get() = when {
                loading -> "正在查询使用时段…"
                error != null -> "使用时段查询失败：$error"
                slots.isEmpty() -> "未配置使用时段（全天可用）"
                else -> UseTimeSlot.summary(slots) +
                    if (UseTimeSlot.isNowAllowed(slots)) "（当前可用）" else "（当前不在时段内）"
            }

        val allowedNow: Boolean get() = slots.isEmpty() || UseTimeSlot.isNowAllowed(slots)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /** 缓存命中就直接显示，没命中才发请求。 */
    fun load(context: Context, bigTypeId: Int) = load(ApiConfig.moduleIdFor(bigTypeId))

    fun load(moduleId: Int) {
        val cached = UseTimeSlot.cached(moduleId)
        if (cached != null) {
            _state.value = State(slots = cached, moduleId = moduleId)
            return
        }
        if (_state.value.loading && _state.value.moduleId == moduleId) {
            return
        }
        _state.value = State(loading = true, moduleId = moduleId)
        executor.execute {
            var result: List<UseTimeSlot>? = null
            var error: String? = null
            try {
                result = WaterApi.queryUseTime(moduleId)
            } catch (e: Exception) {
                error = e.message
            }
            val slots = result ?: emptyList()
            val failure = error
            main.post {
                _state.value = if (failure != null) {
                    State(error = failure, moduleId = moduleId)
                } else {
                    State(slots = slots, moduleId = moduleId)
                }
            }
        }
    }

    /** 扫描到设备时顺手预取，点进用水页就不用等。 */
    fun prefetch(moduleId: Int) {
        if (UseTimeSlot.cached(moduleId) != null) {
            return
        }
        load(moduleId)
    }
}
