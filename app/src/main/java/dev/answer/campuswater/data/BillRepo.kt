package dev.answer.campuswater.data

import dev.answer.campuswater.api.ApiException
import dev.answer.campuswater.api.BillRecord
import dev.answer.campuswater.api.WaterApi
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 账单数据。
 *
 * <p>服务端只按"月"返回整月账单，没有分页，所以一次拉完在内存里做汇总即可。
 */
object BillRepo {

    data class State(
        val loading: Boolean = false,
        val month: String = "",
        val bills: List<BillRecord> = emptyList(),
        val error: String? = null,
        /** 服务端说"未获取到账户信息"：不是没网，是凭据过期，界面上要给"去登录"而不是"重试"。 */
        val sessionExpired: Boolean = false,
    ) {
        val total: Double get() = bills.sumOf { it.money }
        val count: Int get() = bills.size

        /** 近 7 天合计：按账单时间自己筛，服务端没有这个口径。 */
        val last7Days: Double
            get() {
                val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
                return bills.filter { WaterApi.parseBillTime(it.consumeDate) >= cutoff }
                    .sumOf { it.money }
            }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    fun clearError() {
        _state.value = _state.value.copy(error = null, sessionExpired = false)
    }

    fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    /** 当前月，格式与服务端一致：{@code 2026-10}。 */
    fun currentMonth(): String {
        val c = Calendar.getInstance()
        return String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    /** 往前或往后挪 n 个月，用于月份切换。 */
    fun shiftMonth(month: String, delta: Int): String {
        val parts = month.split("-")
        if (parts.size != 2) {
            return currentMonth()
        }
        val c = Calendar.getInstance()
        c.set(parts[0].toInt(), parts[1].toInt() - 1 + delta, 1)
        return String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    /** "2026-10" → "2026 年 10 月"。 */
    fun monthLabel(month: String): String {
        val parts = month.split("-")
        if (parts.size != 2) {
            return month
        }
        return "${parts[0]} 年 ${parts[1].toInt()} 月"
    }

    fun load(month: String = currentMonth()) {
        if (_state.value.loading) {
            return
        }
        _state.value = _state.value.copy(loading = true, month = month, error = null)
        executor.execute {
            var result: List<BillRecord> = emptyList()
            var failure: String? = null
            var expired = false
            try {
                result = WaterApi.queryBills(month)
            } catch (e: ApiException) {
                expired = e.isSessionExpired
                failure = if (expired) "登录凭据已失效，请重新登录"
                else (e.errorMessage ?: "查询账单失败")
            } catch (e: Exception) {
                failure = e.message ?: "查询账单失败"
            }
            val bills = result
            val error = failure
            error?.let { RunLog.add("账单", it) }
            _state.value = State(month = month, bills = bills, error = error, sessionExpired = expired)
        }
    }
}
