package dev.answer.campuswater.ble;

import android.bluetooth.BluetoothDevice;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import dev.answer.campuswater.api.AesUtil;
import dev.answer.campuswater.api.ApiException;
import dev.answer.campuswater.api.DeviceRecord;
import dev.answer.campuswater.api.RateOrder;
import dev.answer.campuswater.api.Session;
import dev.answer.campuswater.api.Settlement;
import dev.answer.campuswater.api.WaterApi;

/**
 * 用水流程状态机，复刻 GoShowerV3 的时序。
 *
 * <pre>
 *  点击"开始用水" → queryDevice("23")
 *    设备回 0x23 → 状态 00 且本次要下单 → downRate_water
 *        → POST .../bluetooth/rateOrder 得到 downData
 *          → BLE 写 CMD 0x21
 *            → 设备回 0x21 → 出水成功，UI 变"结束用水"
 *
 *  点击"结束用水" → queryDevice("22")
 *    设备回 0x22 → 再 queryDevice("23") 刷新
 *      状态非 00/01/02 → queryDevice("85") 提取消费记录
 *        → POST /order/upload/bluetooth/data → 服务端回 AES 密文 clData
 *          → 解密取 "-" 后半段 → BLE 写 CMD 0x86 清零
 * </pre>
 *
 * <p>GATT 操作由 {@link BleClient} 串行化；网络调用走传入的 io Handler。
 */
public class WaterController implements BleClient.Listener {

    private static final String TAG = "WaterController";
    private static final long RESPONSE_TIMEOUT_MS = 12000;

    public interface Ui {
        void onLog(String message);

        /** 蓝牙链路就绪。 */
        void onBleReady();

        /** 设备状态刷新。mine 表示"当前使用中的是不是本人"。 */
        void onStatusChanged(DeviceStatus status, boolean mine);

        /**
         * 出水成功。
         *
         * @param order 下单响应，里面带着闲置自动关停时长（秒）与 consumeDate；
         *              可能为 {@code null}（例如设备直接报告"使用中"的恢复场景）
         */
        void onWaterStarted(RateOrder order);

        /**
         * 关阀 + 上报消费记录完成。
         *
         * @param settlement 结算结果；金额可能还是 {@code null}，由上层用账单补查
         */
        void onWaterFinished(Settlement settlement);

        /**
         * 设备里没有可采集的消费记录（超时自动关停后常见）。
         *
         * <p>必须给上层一个"这一单到此为止"的信号，否则上层的补结算防抖标志
         * 会一直挂着，之后的自动关停检测就再也触发不了。
         */
        void onSettleSkipped();

        void onError(String message);

        void onBusy(boolean busy);
    }

    private final BleClient ble;
    private final Ui ui;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler io;

    private DeviceRecord device;
    /** 用户点击"开始用水"后置 true，下一次 0x23 回来时执行下单。 */
    private boolean pendingStart;
    /** 最近一次下单响应，0x21 应答成功时要拿它起倒计时。 */
    private RateOrder pendingOrder;
    /** 本次开始用水的时刻，用来算"用了多久"。 */
    private long startedAtMs;
    /** 正在等 0x85 消费记录，但这次是"超时自动关停后的补结算"而不是用户手动结束。 */
    private boolean settleOnly;
    private DeviceStatus lastStatus;
    private Runnable timeoutTask;
    /** 正在等待响应的命令字，超时重试时要用。 */
    private String pendingCmd = BleFrame.CMD_QUERY;
    /** 是否已经尝试过"无响应写入"回退。 */
    private boolean triedNoResponseWrite;

    public WaterController(BleClient ble, Ui ui, Handler ioHandler) {
        this.ble = ble;
        this.ui = ui;
        this.io = ioHandler;
    }

    public void setDevice(DeviceRecord device) {
        this.device = device;
    }

    public DeviceStatus getLastStatus() {
        return lastStatus;
    }

    // ------------------------------------------------------------- 对外操作

    /** 进入页面或重连成功后查一次状态。 */
    public void queryStatus() {
        pendingStart = false;
        sendCommand(BleFrame.CMD_QUERY);
    }

    /** 点击主按钮。 */
    public void toggle() {
        DeviceStatus st = lastStatus;
        if (st != null && st.isInUse()) {
            endWater();
        } else {
            startWater();
        }
    }

    private void startWater() {
        ui.onBusy(true);
        pendingStart = true;
        sendCommand(BleFrame.CMD_QUERY);   // 先查状态（app-service.js:71580）
    }

    private void endWater() {
        ui.onBusy(true);
        pendingStart = false;
        sendCommand(BleFrame.CMD_END);
    }

    /**
     * 只补结算，不再关阀。
     *
     * <p>用在"闲置到点、设备已经自己停了"的场景：阀是设备自己关的，
     * 但这一单的记录还留在设备里，得走一遍 0x85 采集 + 上报 + 0x86 清零，
     * 否则服务端那侧的预扣款一直挂着。
     */
    public void settleLeftover() {
        ui.onBusy(true);
        pendingStart = false;
        settleOnly = true;
        sendCommand(BleFrame.CMD_RECORD);
    }

    /** 恢复用水会话时把开始时刻回填进来，否则"用时"算不出来。 */
    public void markStartedAt(long epochMs) {
        if (epochMs > 0) {
            startedAtMs = epochMs;
        }
    }

    public long getStartedAtMs() {
        return startedAtMs;
    }

    public DeviceRecord getDevice() {
        return device;
    }

    /** 通知栏"结束用水"用：不管当前状态如何，都走一次关阀。 */
    public void requestEnd() {
        endWater();
    }

    // ------------------------------------------------------------- 发送与超时

    private void sendCommand(String cmd) {
        if (!ble.isReady()) {
            ui.onBusy(false);
            ui.onError("蓝牙未就绪");
            return;
        }
        pendingCmd = cmd;
        ble.send(cmd, "00");   // 查询/结束/提取记录的 DATA 固定为 "00"
        armResponseTimeout();
    }

    private void armResponseTimeout() {
        clearResponseTimeout();
        timeoutTask = () -> {
            timeoutTask = null;
            onResponseTimeout();
        };
        handler.postDelayed(timeoutTask, RESPONSE_TIMEOUT_MS);
    }

    /**
     * 超时处理。
     *
     * <p>最常见的两种"连上了但设备不回话"：写类型不被设备接受、或者通知其实没订阅上。
     * 前者这里自动回退一次（带响应 → 无响应）；后者由 {@link BleClient} 的日志暴露。
     */
    private void onResponseTimeout() {
        if (!triedNoResponseWrite) {
            triedNoResponseWrite = true;
            ble.setWriteType(android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            ui.onLog("设备未响应，改用「无响应写入」重试一次");
            ble.send(pendingCmd, "00");
            armResponseTimeout();
            return;
        }
        Log.w(TAG, "设备响应超时");
        ui.onBusy(false);
        ui.onError("设备响应超时，请重试");
    }

    private void clearResponseTimeout() {
        if (timeoutTask != null) {
            handler.removeCallbacks(timeoutTask);
            timeoutTask = null;
        }
    }

    // ------------------------------------------------------------- 帧分发

    @Override
    public void onFrame(BleFrame.Parsed frame) {
        clearResponseTimeout();
        Log.i(TAG, "收到帧 " + frame);
        switch (frame.cmd) {
            case BleFrame.CMD_QUERY:
                handleStatus(frame.data);
                break;
            case BleFrame.CMD_DOWN_RATE:
                handleDownRateAck(frame);
                break;
            case BleFrame.CMD_END:
                handleEndAck(frame);
                break;
            case BleFrame.CMD_RECORD:
                handleRecord(frame);
                break;
            case BleFrame.CMD_CLEAR:
                handleCleared(frame);
                break;
            default:
                ui.onLog("未处理的命令 0x" + frame.cmd);
        }
    }

    /** 处理 0x23 状态响应（app-service.js:71813）。 */
    private void handleStatus(String data) {
        DeviceStatus st = DeviceStatus.parse(data);
        lastStatus = st;
        if (!st.success) {
            ui.onBusy(false);
            ui.onError("设备返回异常，请重试");
            return;
        }
        boolean mine = isMine(st.accId);

        if (st.isIdle()) {
            if (pendingStart) {
                pendingStart = false;
                requestDownRate(st);
                return;
            }
            ui.onBusy(false);
            ui.onStatusChanged(st, false);
        } else if (st.isInUse()) {
            pendingStart = false;
            ui.onBusy(false);
            ui.onStatusChanged(st, mine);
        } else if (st.isCardConsuming()) {
            pendingStart = false;
            ui.onBusy(false);
            ui.onError("设备正在刷卡消费");
        } else {
            // 其他状态：拉消费记录（app-service.js:71910）
            pendingStart = false;
            ui.onLog("设备状态 " + st.state + "，提取消费记录");
            sendCommand(BleFrame.CMD_RECORD);
        }
    }

    private boolean isMine(long accId) {
        String myAccountId = Session.get().accountId;
        if (myAccountId == null || myAccountId.isEmpty()) {
            return false;
        }
        try {
            return Long.parseLong(myAccountId) == accId;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 向服务端下单，拿到 downData 后写 0x21。 */
    private void requestDownRate(final DeviceStatus st) {
        ui.onLog("正在生成出水报文…");
        io.post(() -> {
            try {
                final RateOrder order = WaterApi.downRate(
                        device, st.randomNumber, st.tactimes, st.commMode);
                pendingOrder = order;
                if (order.hasCountdown()) {
                    ui.onLog("服务端下发闲置自动关停时长：" + order.autoDisConTime + " 秒");
                }

                handler.post(() -> {

                    if (!ble.isReady()) {
                        ui.onBusy(false);
                        ui.onError("蓝牙已断开");
                        return;
                    }
                    ui.onLog("下发 0x21 出水指令");
                    ble.writeFrame(BleFrame.build("80", BleFrame.CMD_DOWN_RATE, "00", order.downData));
                    armResponseTimeout();
                });
            } catch (final Exception e) {
                handler.post(() -> {
                    ui.onBusy(false);
                    ui.onError(describe(e));
                });
            }
        });
    }

    /** 设备对 0x21 的应答（app-service.js:42175）。 */
    private void handleDownRateAck(BleFrame.Parsed frame) {
        ui.onBusy(false);
        if (frame.isOk()) {
            startedAtMs = System.currentTimeMillis();
            ui.onWaterStarted(pendingOrder);
        } else {
            ui.onError("设备拒绝出水指令");
        }
    }

    /** 设备对 0x22 的应答（app-service.js:71966）：成功则再查一次状态。 */
    private void handleEndAck(BleFrame.Parsed frame) {
        if (frame.isOk()) {
            sendCommand(BleFrame.CMD_QUERY);
        } else {
            ui.onBusy(false);
            ui.onError("结束失败，请重试");
        }
    }

    /** 设备吐出的消费记录（app-service.js:72019 → 42237）。 */
    private void handleRecord(BleFrame.Parsed frame) {
        if (!frame.isOk()) {
            ui.onBusy(false);
            if (settleOnly) {
                // 超时自动关停后设备里没有记录可采（多半已经由服务端结清），不算错误
                ui.onLog("设备未返回消费记录，跳过补结算");
                settleOnly = false;
                ui.onSettleSkipped();
            } else {
                ui.onError("采集消费信息失败");
            }
            return;
        }
        final String xfData = frame.data;
        final DeviceStatus st = lastStatus;
        if (st == null) {
            ui.onBusy(false);
            ui.onError("状态丢失，请重新查询");
            return;
        }
        final boolean wasSettleOnly = settleOnly;
        settleOnly = false;
        ui.onLog("上报消费记录…");
        io.post(() -> {
            try {
                JSONObject response = WaterApi.uploadConsume(xfData, st.tactimes, st.commMode);


                JSONObject data = response.optJSONObject("data");
                final String clData = data == null ? "" : data.optString("clData", "");
                final Settlement settlement = Settlement.fromUploadData(data);
                settlement.durationMs = startedAtMs > 0
                        ? System.currentTimeMillis() - startedAtMs : 0;

                // errorCode 0 / 206 / 7 都照样解密并清零（app-service.js:42254-42278）
                if (clData.isEmpty()) {
                    final String msg = response.optString("errorMessage", "服务端未返回清零数据");
                    handler.post(() -> {
                        ui.onBusy(false);
                        if (wasSettleOnly) {
                            ui.onLog("补结算未拿到清零数据：" + msg);
                            ui.onSettleSkipped();
                        } else {
                            ui.onError(msg);
                        }
                    });
                    return;
                }
                final String clear = AesUtil.extractClearData(AesUtil.decrypt(clData));
                handler.post(() -> {
                    if (clear == null || clear.isEmpty()) {
                        ui.onBusy(false);
                        ui.onError("清零数据解密失败");
                        return;
                    }
                    ui.onLog("下发 0x86 清零指令" + clear);
                    ble.writeFrame(BleFrame.build("80", BleFrame.CMD_CLEAR, "00", clear));
                    armResponseTimeout();
                    ui.onWaterFinished(settlement);
                });
            } catch (final Exception e) {
                handler.post(() -> {
                    ui.onBusy(false);
                    ui.onError(describe(e));
                });
            }
        });
    }

    /** 设备对 0x86 的应答：刷新状态回到空闲（app-service.js:71939）。 */
    private void handleCleared(BleFrame.Parsed frame) {
        ui.onBusy(false);
        if (frame.isOk()) {
            sendCommand(BleFrame.CMD_QUERY);
        }
    }

    // ------------------------------------------------------------- BleClient.Listener

    @Override
    public void onDeviceFound(BluetoothDevice device, String advMac, int rssi) {
        // 扫描结果由 Activity 处理
    }

    @Override
    public void onReady() {
        triedNoResponseWrite = false;
        ui.onBleReady();
        // 小程序在订阅成功后等 200ms 才发第一条命令（app-service.js:71666）
        handler.postDelayed(this::queryStatus, 300);
    }

    @Override
    public void onDisconnected() {
        ui.onBusy(false);
        ui.onError("蓝牙连接已断开");
    }

    @Override
    public void onFailure(String message) {
        ui.onBusy(false);
        ui.onError(message);
    }

    @Override
    public void onLog(String message) {
        ui.onLog(message);
    }

    // ------------------------------------------------------------- 工具

    /** 把异常翻译成用户能看懂的中文。 */
    public static String describe(Exception e) {
        if (e instanceof ApiException) {
            ApiException ae = (ApiException) e;
            if (ae.isNotEnoughBalance()) {
                return "余额不足，请先充值";
            }
            if (ae.isAccountDisabled()) {
                return "该项目账户已禁用";
            }
            if (ae.isNeedBindProject()) {
                return "请先绑定学校";
            }
            return ae.errorMessage + "（" + ae.errorCode + "）";
        }
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    public void release() {
        clearResponseTimeout();
        handler.removeCallbacksAndMessages(null);
    }
}
