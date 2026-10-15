package dev.answer.campuswater.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import dev.answer.campuswater.api.ApiConfig
import dev.answer.campuswater.api.DeviceRecord
import dev.answer.campuswater.api.RateOrder
import dev.answer.campuswater.api.Session
import dev.answer.campuswater.api.Settlement
import dev.answer.campuswater.api.WaterApi
import dev.answer.campuswater.ble.BleClient
import dev.answer.campuswater.ble.BleFrame
import dev.answer.campuswater.ble.DeviceStatus
import dev.answer.campuswater.ble.WaterController
import dev.answer.campuswater.notify.Notifier
import dev.answer.campuswater.service.WaterService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 用水会话的唯一持有者。
 *
 * <p>蓝牙链路、状态机、倒计时基准、结算结果都只在这里改，界面（Compose）只读
 * [state] 和 [events]。之所以不放在 Activity 里：
 * 划掉最近任务之后进程还在（前台服务撑着），但 Activity 已经没了 ——
 * 会话必须活得比界面久。
 */
object WaterEngine : WaterController.Ui, BleClient.Listener {

    private lateinit var appContext: Context
    private var ioThread: HandlerThread? = null
    private var io: Handler? = null
    private val main = Handler(Looper.getMainLooper())

    private var ble: BleClient? = null
    private var controller: WaterController? = null

    /** 用户点了"结束用水"，此时看到设备空闲应该补走结算流程，而不是当成"自动关停"。 */
    private var awaitingManualEnd = false

    /** 正在走补结算，避免状态刷新又触发一次。 */
    private var settling = false

    /** 这一单是不是设备自己关的（超时自动关停）。 */
    private var lastAutoClosed = false

    /** 这一单的闲置时长（秒），到点后用户选"继续用水"要靠它重新计时。 */
    private var autoDisconSec = 0

    /** 倒计时归零只提醒一次，否则每秒都会弹一遍确认框。 */
    private var deadlineNotified = false

    private val _state = MutableStateFlow(WaterUiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<WaterEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        AppSettings.init(appContext)
        WaterStore.init(appContext)
        Notifier.ensureChannels(appContext)
        if (ioThread == null) {
            ioThread = HandlerThread("water-engine-io").also { it.start() }
            io = Handler(ioThread!!.looper)
        }
        // 进程被杀后重进：把上一次的现场读回来，界面立刻能显示"正在用水 + 倒计时"
        restoreFromStore()
    }

    private fun restoreFromStore() {
        val device = WaterStore.device() ?: return
        if (!WaterStore.isActive) {
            return
        }
        _state.value = _state.value.copy(
            device = device,
            phase = EnginePhase.DISCONNECTED,
            inUse = true,
            usedByMe = true,
            startedAtMs = WaterStore.startedAtMs,
            deadlineMs = WaterStore.deadlineMs,
            statusText = "检测到进行中的用水，重新连接设备中…",
            statusTone = Tone.WARN,
        )
    }

    // -------------------------------------------------------------- 连接

    /** 建立蓝牙连接并开始查询状态。 */
    @SuppressLint("MissingPermission")
    fun connect(device: DeviceRecord) {
        if (io == null) {
            init(appContext)
        }
        _state.value = _state.value.copy(
            device = device,
            phase = EnginePhase.CONNECTING,
            busy = true,
            statusText = "正在连接设备…",
            statusTone = Tone.NEUTRAL,
        )
        log("连接 " + device.bleAddress)

        controller?.release()
        ble?.close()
        val client = BleClient(appContext, this)
        val ctrl = WaterController(client, this, io!!)
        ctrl.setDevice(device)
        ctrl.markStartedAt(WaterStore.startedAtMs)
        ble = client
        controller = ctrl

        if (!client.init()) {
            _state.value = _state.value.copy(
                phase = EnginePhase.CONNECT_FAILED, busy = false,
                statusText = "蓝牙未开启", statusTone = Tone.ERROR,
            )
            return
        }
        try {
            // getDefaultAdapter() 已废弃，改走 BluetoothManager（同一底层对象，只是不再报警告）
            val adapter = appContext
                .getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            if (adapter == null) {
                _state.value = _state.value.copy(
                    phase = EnginePhase.CONNECT_FAILED, busy = false,
                    statusText = "设备不支持蓝牙", statusTone = Tone.ERROR,
                )
                return
            }
            client.connect(adapter.getRemoteDevice(BleClient.normalizeVendorPrefix(device.bleAddress)))
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                phase = EnginePhase.CONNECT_FAILED, busy = false,
                statusText = "连接失败：" + e.message, statusTone = Tone.ERROR,
            )
        }
    }

    fun disconnect() {
        controller?.release()
        controller = null
        ble?.close()
        ble = null
        _state.value = _state.value.copy(
            phase = EnginePhase.DISCONNECTED, busy = false,
            statusText = "已断开连接", statusTone = Tone.NEUTRAL,
        )
    }

    val hasSession: Boolean get() = _state.value.phase == EnginePhase.READY

    fun queryStatus() {
        controller?.queryStatus()
    }

    /** 主按钮：空闲就开、使用中就关。 */
    fun toggle() {
        val ctrl = controller ?: return
        if (_state.value.inUse) {
            requestEnd()
        } else {
            awaitingManualEnd = false
            ctrl.toggle()
        }
    }

    /** 通知栏「结束用水」与界面按钮共用这条路径。 */
    fun requestEnd() {
        val ctrl = controller ?: run {
            emit(WaterEvent.Banner(BannerKind.CLOSE_FAILED, "无法结束用水",
                "蓝牙未连接，请到设备附近重新连接后结束"))
            return
        }
        awaitingManualEnd = true
        ctrl.requestEnd()
    }

    /** 只补结算（设备已经自己停了）。 */
    private fun settleLeftover(autoClosed: Boolean) {
        val ctrl = controller ?: return
        settling = true
        lastAutoClosed = autoClosed
        ctrl.settleLeftover()
    }

    // -------------------------------------------------------------- 倒计时

    /** 闲置自动关停剩余秒数（0 = 服务端没下发时长）。 */
    fun countdownRemainSec(): Int = _state.value.countdownRemainSec(System.currentTimeMillis())

    /**
     * 倒计时归零。界面轮询与服务轮询都会调这里，只生效一次。
     *
     * <p>做两件事：给界面发确认框事件；顺手查一次设备状态 ——
     * 设备多半已经自己停了，状态回调里会走补结算。
     */
    fun markDeadlineReached() {
        if (deadlineNotified || !_state.value.inUse) {
            return
        }
        deadlineNotified = true
        if (AppSettings.autoEndAtDeadline) {
            // 用户选了"到点自动结束"：直接发关阀指令，不再等他确认
            emit(WaterEvent.Banner(
                BannerKind.AUTO_CLOSED, "已到设定的超时时间",
                "已自动结束这一单，正在结算。"
            ))
            Notifier.showEvent(
                appContext, "已到超时时间，自动结束用水",
                "关阀指令已下发，结算结果稍后见通知。", bannerable = true
            )
            requestEnd()
            return
        }
        emit(WaterEvent.CountdownElapsed(_state.value.lastSettlement))
        // 界面活着会弹确认框；不在前台时这条通知就是唯一提示
        Notifier.showEvent(
            appContext, "闲置自动关停时间已到",
            "设备可能已经自己关阀。回到应用里确认继续用水还是结束这一单。",
            bannerable = true,
        )
        queryStatus()
    }

    /** 「后台用水监控」开关被打开、而此刻正在用水时，立刻把服务拉起来。 */
    fun startMonitorIfNeeded() {
        if (AppSettings.monitorEnabled && _state.value.inUse) {
            startMonitor()
        }
    }

    /** 确认框里选"继续用水"：按同一时长重新起一个倒计时。 */
    fun extendDeadline() {
        if (autoDisconSec <= 0) {
            return
        }
        val deadline = System.currentTimeMillis() + autoDisconSec * 1000L
        WaterStore.updateDeadline(deadline)
        deadlineNotified = false
        _state.value = _state.value.copy(deadlineMs = deadline)
        log("已继续用水，重新计时 ${autoDisconSec} 秒")
    }

    /**
     * 服务轮询里调用：到点了就检查一次设备状态。
     *
     * @return 下一次唤醒的延迟毫秒。临近关停时精确睡到点，平时 15 秒一问。
     */
    fun monitorDelayMs(): Long {
        val remain = countdownRemainSec()
        if (remain <= 0) {
            return 15_000L
        }
        return if (remain < 15) remain * 1000L else 15_000L
    }

    // -------------------------------------------------------------- WaterController.Ui

    override fun onLog(message: String) = log(message)

    override fun onBleReady() {
        _state.value = _state.value.copy(
            phase = EnginePhase.READY, busy = true,
            statusText = "已连接，正在查询设备状态…", statusTone = Tone.NEUTRAL,
        )
    }

    override fun onStatusChanged(status: DeviceStatus, mine: Boolean) {
        val now = System.currentTimeMillis()
        val inUse = status.isInUse()
        val deadline = _state.value.deadlineMs
        _state.value = _state.value.copy(
            busy = false,
            phase = EnginePhase.READY,
            inUse = inUse,
            usedByMe = mine,
            statusText = when {
                status.isIdle() -> "设备空闲"
                inUse && mine -> "正在用水（本账号）"
                inUse -> "正在被他人使用"
                else -> "设备状态：${status.state}"
            },
            statusTone = when {
                status.isIdle() -> Tone.NEUTRAL
                inUse -> Tone.OK
                else -> Tone.WARN
            },
        )
        if (inUse) {
            if (!mine) {
                emit(WaterEvent.Banner(BannerKind.OCCUPIED, "设备正被他人使用",
                    "这台设备当前由别的账号使用，请勿强行中断"))
            }
            if (deadline > 0 && now > deadline && !settling) {
                log("已超过闲置时限，设备仍在使用中，继续监控")
            }
            keepInUseNotification()
        } else if (status.isIdle() && WaterStore.isActive && !settling) {
            // 设备自己停了（没人按结束），这一单的记录还留在设备里 —— 补结算
            log("设备已自行关停，开始补结算这一单")
            settleLeftover(autoClosed = !awaitingManualEnd)
        }
    }

    override fun onWaterStarted(order: RateOrder?) {
        val now = System.currentTimeMillis()
        val device = _state.value.device ?: return
        // 服务端下发的优先；用户改成手动指定时用他的分钟数
        val seconds = if (AppSettings.timeoutFollowServer) {
            order?.autoDisConTime ?: 0
        } else {
            AppSettings.timeoutMinutes * 60
        }
        val deadline = if (seconds > 0) now + seconds * 1000L else 0L
        WaterStore.markActive(device, now, deadline)
        autoDisconSec = seconds
        awaitingManualEnd = false
        settling = false
        lastAutoClosed = false
        deadlineNotified = false
        _state.value = _state.value.copy(
            busy = false, inUse = true, usedByMe = true,
            startedAtMs = now, deadlineMs = deadline,
            autoDisconSec = autoDisconSec, lastSettlement = null,
            statusText = "正在用水", statusTone = Tone.OK,
        )
        log("出水成功" + if (deadline > 0)
            "，闲置 ${order!!.autoDisConTime} 秒后自动关停" else "（服务端未下发自动关停时长）")
        startMonitor()
        keepInUseNotification()
    }

    override fun onWaterFinished(settlement: Settlement) {
        settling = false
        awaitingManualEnd = false
        val info = SettlementInfo.of(settlement, lastAutoClosed)
        val finishedAt = System.currentTimeMillis()
        val durationMs = if (info.durationMs > 0) info.durationMs
        else (finishedAt - _state.value.startedAtMs).coerceAtLeast(0)
        val withDuration = info.copy(durationMs = durationMs)
        WaterStore.markFinished(finishedAt)
        withDuration.moneyYuan?.let {
            WaterStore.recordSettlement(it, durationMs, withDuration.consumeTime)
        }
        _state.value = _state.value.copy(
            busy = false, inUse = false, usedByMe = false, lastSettlement = withDuration,
            statusText = if (lastAutoClosed) "已自动关停，正在结算…" else "已结束，正在结算…",
            statusTone = Tone.WARN,
        )
        Notifier.cancelInUse(appContext)
        emit(WaterEvent.Finished(withDuration))
        Notifier.showFinished(appContext, withDuration)
        log("本单结束：用时 ${Settlement.formatDuration(durationMs)}，" +
                "金额 ${withDuration.moneyYuan?.let { "¥%.2f".format(it) } ?: "结算中"}")
        if (withDuration.pending) {
            settleRetryLoop(withDuration)
        }
    }

    /**
     * 设备里没有可采集的消费记录 —— 这一单到此为止。
     *
     * <p>常见于"闲置到点、设备自己关了阀"：设备侧不留记录，结算多半已由服务端处理。
     * 这里必须把状态收干净，否则补结算防抖标志会一直挂着，
     * 之后的自动关停检测再也触发不了。
     */
    override fun onSettleSkipped() {
        settling = false
        awaitingManualEnd = false
        val now = System.currentTimeMillis()
        val durationMs = _state.value.startedAtMs.takeIf { it > 0 }?.let { now - it } ?: 0L
        val info = SettlementInfo(
            durationMs = durationMs,
            autoClosed = lastAutoClosed,
            source = "设备无消费记录",
        )
        WaterStore.markFinished(now)
        _state.value = _state.value.copy(
            busy = false, inUse = false, usedByMe = false, deadlineMs = 0L,
            lastSettlement = info,
            statusText = "设备空闲", statusTone = Tone.NEUTRAL,
        )
        Notifier.cancelInUse(appContext)
        emit(WaterEvent.Finished(info))
        Notifier.showFinished(appContext, info)
        log("本单未见消费记录，金额以服务端账单为准")
    }

    override fun onError(message: String) {
        // 服务端把"未获取到用户信息，请重新登录"（errorCode 8）当业务错误返回，
        // 这种情况留在原页面无意义：清凭据、回登录页
        if (message.contains("重新登录")) {
            AuthState.markExpired()
        }
        settling = false
        awaitingManualEnd = false
        _state.value = _state.value.copy(
            busy = false, statusText = message, statusTone = Tone.ERROR,
        )
        val openFailed = message.contains("拒绝出水") || message.contains("未响应") ||
            message.contains("超时") || message.contains("蓝牙")
        emit(WaterEvent.Banner(
            if (openFailed) BannerKind.OPEN_FAILED else BannerKind.INFO,
            if (openFailed) "开阀失败" else "出错了", message))
        log("错误：$message")
    }

    override fun onBusy(busy: Boolean) {
        _state.value = _state.value.copy(busy = busy)
    }

    /**
     * 结算金额还没出来时用账单列表补查。
     *
     * <p>账单会先以占位金额出现、结算完才填真值，所以只认 >0 的结果；
     * 几轮都拿不到就保持"结算中"，不能报 ¥0.00 骗用户。
     */
    private fun settleRetryLoop(base: SettlementInfo) {
        val startedAt = _state.value.startedAtMs.takeIf { it > 0 } ?: WaterStore.startedAtMs
        io?.postDelayed(object : Runnable {
            var round = 0
            override fun run() {
                round++
                io!!.post {
                    var probed: Double? = null
                    try {
                        probed = WaterApi.queryConsumeMoneyFromBill("", startedAt)
                    } catch (e: Exception) {
                        log("结算补查失败：${e.message}")
                    }
                    // 捕获变量不能智能转换，先固定成 val 再用
                    val money = probed
                    main.post {
                        if (money != null && money > 0) {
                            val updated = base.copy(moneyYuan = money, source = "账单列表")
                            WaterStore.recordSettlement(money, base.durationMs, base.consumeTime)
                            _state.value = _state.value.copy(lastSettlement = updated)
                            Notifier.showFinished(appContext, updated)
                            log("结算补查命中：¥%.2f".format(money))
                        } else if (round < 5) {
                            main.postDelayed(this, 1500L)
                        } else {
                            log("结算补查未命中，金额以服务端口径为准，请稍后在账单里查看")
                        }
                    }
                }
            }
        }, 1200L)
    }

    // -------------------------------------------------------------- BleClient.Listener

    /** 扫描结果由 [ScanEngine] 处理，这里只是接口占位。 */
    override fun onDeviceFound(device: android.bluetooth.BluetoothDevice, advMac: String, rssi: Int) = Unit

    override fun onReady() {
        controller?.onReady()
    }

    override fun onDisconnected() {
        controller?.onDisconnected()
    }

    override fun onFrame(frame: BleFrame.Parsed) {
        controller?.onFrame(frame)
    }

    override fun onFailure(message: String) {
        controller?.onFailure(message)
    }

    // -------------------------------------------------------------- 通知与服务

    private fun keepInUseNotification() {
        val s = _state.value
        val device = s.device ?: return
        if (!s.inUse) {
            return
        }
        Notifier.showInUse(appContext, deviceLabel(device), s.startedAtMs, true)
    }

    fun deviceLabel(device: DeviceRecord): String =
        (if (device.deviceName.isNullOrEmpty()) "设备 ${device.devId}" else device.deviceName) +
            " · " + ApiConfig.bigTypeName(device.bigTypeId)

    /** 用水开始/恢复时把前台服务拉起来，监控循环与常驻通知由它维持。 */
    private fun startMonitor() {
        if (!AppSettings.monitorEnabled) {
            return
        }
        val intent = Intent(appContext, WaterService::class.java)
            .setAction(WaterService.ACTION_MONITOR)
        try {
            appContext.startForegroundService(intent)
        } catch (e: Exception) {
            log("后台监控服务启动失败：${e.message}")
        }
    }

    // -------------------------------------------------------------- 工具

    private fun emit(event: WaterEvent) {
        _events.tryEmit(event)
    }

    /** 只记录不展示：查看入口在「个人 → 运行日志」。 */
    private fun log(message: String) {
        RunLog.add("用水", message)
    }
}

/**
 * 蓝牙扫描会话。
 *
 * <p>和 [WaterEngine] 一样放在进程级：切标签页、旋转屏幕都不该打断正在进行的扫描。
 */
object ScanEngine : BleClient.Listener {

    private lateinit var appContext: Context
    private var ioThread: HandlerThread? = null
    private var io: Handler? = null
    private var ble: BleClient? = null

    private val _state = MutableStateFlow(ScanState())
    val state = _state.asStateFlow()

    /** 扫描命中后由界面决定去留；这里只负责把 MAC 换成设备档案。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (ioThread == null) {
            ioThread = HandlerThread("scan-io").also { it.start() }
            io = Handler(ioThread!!.looper)
        }
    }

    fun start(context: Context) {
        init(context)
        _state.value = _state.value.copy(
            scanning = true,
            devices = emptyList(),
            message = "正在扫描 ${BleFrame.DEVICE_NAME} …",
        )
        val client = BleClient(appContext, this)
        ble?.close()
        ble = client
        if (!client.init()) {
            _state.value = _state.value.copy(scanning = false, message = "蓝牙未开启，请先打开系统蓝牙")
            return
        }
        client.startScan()
    }

    fun stop() {
        ble?.stopScan()
        _state.value = _state.value.copy(scanning = false)
    }

    fun release() {
        ble?.close()
        ble = null
        ioThread?.quitSafely()
        ioThread = null
        io = null
        _state.value = ScanState()
    }

    override fun onDeviceFound(device: android.bluetooth.BluetoothDevice, advMac: String, rssi: Int) {
        log("发现设备 ${device.address}（信号 $rssi）")
        _state.value = _state.value.copy(message = "已发现设备，正在查询设备档案…")
        resolve(device.address, advMac)
    }

    override fun onReady() {}
    override fun onDisconnected() {}
    override fun onFrame(frame: BleFrame.Parsed) {}

    override fun onFailure(message: String) {
        _state.value = _state.value.copy(scanning = false, message = message)
        log(message)
    }

    override fun onLog(message: String) = log(message)
    /** 用 MAC 换设备档案；系统地址拿不到时再用广播里解析出的 MAC 试一次。 */
    private fun resolve(bleAddress: String, advMac: String) {
        val session = Session.get()
        if (session.projectId.isNullOrEmpty()) {
            _state.value = _state.value.copy(message = "账号尚未绑定学校，无法查询设备")
            return
        }
        io!!.post {
            val used = BleClient.normalizeVendorPrefix(bleAddress)
            var found: List<DeviceRecord> = emptyList()
            var finalUsed = used
            var error: String? = null
            try {
                found = WaterApi.findDevices(used)
                if (found.isEmpty() && advMac.isNotEmpty() && !advMac.equals(used, true)) {
                    finalUsed = advMac
                    found = WaterApi.findDevices(advMac)
                }
            } catch (e: Exception) {
                error = e.message
            }
            val result = found
            val usedMac = finalUsed
            Handler(Looper.getMainLooper()).post {
                if (error != null) {
                    _state.value = _state.value.copy(
                        scanning = false, message = "查询设备失败：$error"
                    )
                    log("查询设备失败：$error")
                    return@post
                }
                if (result.isEmpty()) {
                    _state.value = _state.value.copy(
                        scanning = false, message = "该设备未绑定项目（MAC=$usedMac）"
                    )
                    return@post
                }
                // 使用时段是按设备类型（模块）配的，拿到设备才知道查哪个
                UseTimeRepo.prefetch(ApiConfig.moduleIdFor(result[0].bigTypeId))
                _state.value = _state.value.copy(
                    scanning = false,
                    devices = result,
                    message = "共 ${result.size} 台设备，点击进入",
                )
            }
        }
    }

    private fun log(message: String) {
        RunLog.add("扫描", message)
    }
}
