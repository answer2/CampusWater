package dev.answer.campuswater.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * BLE 通信客户端，复刻小程序 GoShowerV3 的蓝牙层。
 *
 * <p>对应关系（小程序 → 本类）：
 * <ul>
 *   <li>{@code breakBleStart/openBluetoothAdapter} → {@link #init()} + {@link #startScan()}</li>
 *   <li>{@code creatConnect}（含 3 次重试）→ {@link #connect(BluetoothDevice)}</li>
 *   <li>{@code _negotiateMtu}（185，失败降级）→ {@link #onMtuChanged}</li>
 *   <li>{@code GetService}（FF00）→ {@link #onServicesDiscovered}</li>
 *   <li>{@code GetCharacter}（FF01/FF02）→ {@link #onServicesDiscovered}</li>
 *   <li>{@code lanya9}（订阅通知）→ {@link #enableNotification}</li>
 *   <li>{@code lanya8}（分片 + "16" 对齐）→ {@link #chunkFrame(String)}</li>
 *   <li>{@code BleWrite}（busy/10012 重试 5 次）→ {@link #onCharacteristicWrite}</li>
 *   <li>{@code onBLECharacteristicValueChange}（粘包重组）→ {@link #appendAndDispatch}</li>
 * </ul>
 */
public class BleClient {

    private static final String TAG = "BleClient";
    private static final long CONNECT_TIMEOUT_MS = 8000;
    private static final int MAX_CONNECT_RETRY = 3;
    private static final int MAX_WRITE_RETRY = 5;
    private static final int REQUEST_MTU = 185;

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void onDeviceFound(BluetoothDevice device, String advMac, int rssi);

        void onReady();

        void onDisconnected();

        void onFrame(BleFrame.Parsed frame);

        void onFailure(String message);

        void onLog(String message);
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;

    private String deviceAddress = "";
    private String advMac = "";
    private volatile boolean ready;
    private boolean closed;
    private int connectRetry;
    private int chunkSize = 20;

    /** 写类型。默认带响应；设备不接受时可切到 {@code WRITE_TYPE_NO_RESPONSE} 重试。 */
    private int writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

    private final StringBuilder rxBuffer = new StringBuilder();
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private boolean writing;
    private int writeRetry;

    public BleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        // Android 的 BluetoothGattCallback 回调发生在 binder 线程而不是主线程，
        // 因此统一包一层，保证 UI 代码只在主线程被调用。
        this.listener = new MainThreadListener(listener, handler);
    }

    /** 把所有回调切到主线程，避免子线程直接操作 View。 */
    private static final class MainThreadListener implements Listener {
        private final Listener inner;
        private final Handler h;

        MainThreadListener(Listener inner, Handler h) {
            this.inner = inner;
            this.h = h;
        }

        @Override
        public void onDeviceFound(BluetoothDevice device, String advMac, int rssi) {
            h.post(() -> inner.onDeviceFound(device, advMac, rssi));
        }

        @Override
        public void onReady() {
            h.post(inner::onReady);
        }

        @Override
        public void onDisconnected() {
            h.post(inner::onDisconnected);
        }

        @Override
        public void onFrame(BleFrame.Parsed frame) {
            h.post(() -> inner.onFrame(frame));
        }

        @Override
        public void onFailure(String message) {
            h.post(() -> inner.onFailure(message));
        }

        @Override
        public void onLog(String message) {
            h.post(() -> inner.onLog(message));
        }
    }

    // ------------------------------------------------------------------ 初始化

    public boolean init() {
        BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    public boolean isReady() {
        return ready;
    }

    public String getDeviceAddress() {
        return deviceAddress;
    }

    /** 切换写类型，用于"写成功但设备不响应"时的自动回退。 */
    public void setWriteType(int type) {
        this.writeType = type;
        log("切换写类型为 " + describeWriteType(type));
    }

    public int getWriteType() {
        return writeType;
    }

    // ------------------------------------------------------------------ 扫描

    @SuppressLint("MissingPermission")
    public void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            listener.onFailure("蓝牙未开启");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onFailure("无法获取蓝牙扫描器");
            return;
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner.startScan(Collections.<ScanFilter>emptyList(), settings, scanCallback);
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device == null) {
                return;
            }
            String name = null;
            try {
                name = device.getName();
            } catch (SecurityException ignored) {
            }
            if (name == null || !BleFrame.DEVICE_NAME.equals(name)) {
                return;
            }
            advMac = deriveAdvMac(result);
            stopScan();
            listener.onDeviceFound(device, advMac, result.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            listener.onFailure("扫描失败，错误码 " + errorCode);
        }
    };

    /**
     * 从广播数据推导 MAC。
     *
     * <p><b>与小程序的区别</b>：微信的 {@code advertisData} 只给厂商自定义数据段
     * （2 字节公司 ID + 载荷），跳过前 2 字节即得 6 字节 MAC；
     * 而 Android 的 {@code ScanRecord.getBytes()} 返回<b>完整 AD 结构</b>，
     * 照搬会得到一整串垃圾。故改用 {@code getManufacturerSpecificData()}。
     */
    private String deriveAdvMac(ScanResult result) {
        try {
            ScanRecord record = result.getScanRecord();
            if (record == null) {
                return "";
            }
            android.util.SparseArray<byte[]> manu = record.getManufacturerSpecificData();
            if (manu == null || manu.size() == 0) {
                return "";
            }
            byte[] payload = manu.valueAt(0);
            if (payload == null || payload.length < 6) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format(Locale.US, "%02X", payload[i] & 0xFF));
            }
            return normalizeVendorPrefix(sb.toString());
        } catch (Exception e) {
            return "";
        }
    }

    /** C0:15:83 → 00:15:83（app-service.js:72084、72041）。 */
    public static String normalizeVendorPrefix(String mac) {
        if (mac == null) {
            return "";
        }
        String up = mac.toUpperCase(Locale.US);
        if (up.startsWith("C0:15:83")) {
            return "00:15:83" + up.substring(8);
        }
        return up;
    }

    // ------------------------------------------------------------------ 连接

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device) {
        deviceAddress = device.getAddress().toUpperCase(Locale.US);
        connectRetry = 0;
        closed = false;
        ready = false;
        doConnect(device);
    }

    @SuppressLint("MissingPermission")
    private void doConnect(final BluetoothDevice device) {
        log("createBLEConnection -> " + device.getAddress()
                + "（第 " + (connectRetry + 1) + " 次）");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } else {
            gatt = device.connectGatt(context, false, gattCallback);
        }
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    }

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            if (!ready) {
                log("连接超时（" + CONNECT_TIMEOUT_MS + "ms 内未就绪）");
                retryConnect();
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void retryConnect() {
        closeGatt();
        if (connectRetry >= MAX_CONNECT_RETRY) {
            listener.onFailure("蓝牙连接失败，请重试");
            return;
        }
        connectRetry++;
        try {
            doConnect(adapter.getRemoteDevice(deviceAddress));
        } catch (Exception e) {
            listener.onFailure("蓝牙连接失败：" + e.getMessage());
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("已连接，请求 MTU " + REQUEST_MTU);
                handler.removeCallbacks(connectTimeout);
                g.requestMtu(REQUEST_MTU);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.removeCallbacks(connectTimeout);
                ready = false;
                log("连接断开（status=" + status + "）");
                if (!closed) {
                    listener.onDisconnected();
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 22) {
                chunkSize = Math.max(20, mtu - 3);
                log("MTU = " + mtu + "，分片 " + chunkSize);
            } else {
                chunkSize = 20;
                log("MTU 协商失败(status=" + status + ", mtu=" + mtu + ")，按 23/20 处理");
            }
            discoverServices();
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onFailure("服务发现失败（status=" + status + "）");
                return;
            }

            BluetoothGattService target = null;
            for (BluetoothGattService s : g.getServices()) {
                String u = s.getUuid().toString().toUpperCase(Locale.US);
                log("服务 " + u);
                if (u.contains(BleFrame.SERVICE_HINT)) {
                    target = s;
                }
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    log("    特征 " + c.getUuid().toString().toUpperCase(Locale.US)
                            + "  [" + describeProperties(c.getProperties()) + "]");
                }
            }
            if (target == null) {
                listener.onFailure("未找到 FF00 服务");
                return;
            }

            notifyChar = null;
            writeChar = null;
            for (BluetoothGattCharacteristic c : target.getCharacteristics()) {
                String u = c.getUuid().toString().toUpperCase(Locale.US);
                if (u.contains(BleFrame.NOTIFY_HINT)) {
                    notifyChar = c;
                }
                if (u.contains(BleFrame.WRITE_HINT)) {
                    writeChar = c;
                }
            }
            if (writeChar == null) {
                listener.onFailure("未找到写特征 FF02");
                return;
            }
            log("写特征 " + writeChar.getUuid() + "，通知特征 "
                    + (notifyChar == null ? "（缺失）" : notifyChar.getUuid()));
            enableNotification(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("通知已订阅（CCCD 写入成功）");
                ready = true;
                listener.onReady();
            } else {
                listener.onFailure("订阅通知失败（status=" + status + "）");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] value = c.getValue();
            if (value != null) {
                appendAndDispatch(value);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            // API 33+ 走这里；不单独覆写的话 Android 13 及以上收不到设备响应
            if (value != null && value.length > 0) {
                appendAndDispatch(value);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                writing = false;
                writeRetry = 0;
                log("写入成功 " + describeWriteType(writeType));
                handler.post(BleClient.this::writeNext);
            } else {
                writing = false;
                if (writeRetry < MAX_WRITE_RETRY) {
                    writeRetry++;
                    log("写入失败(status=" + status + ")，第 " + writeRetry + " 次重试");
                    handler.postDelayed(BleClient.this::writeNext, 150L * writeRetry);
                } else {
                    writeRetry = 0;
                    synchronized (writeQueue) {
                        writeQueue.clear();
                    }
                    listener.onFailure("蓝牙写入失败，请重试");
                }
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void discoverServices() {
        if (gatt != null) {
            gatt.discoverServices();
        }
    }

    @SuppressLint("MissingPermission")
    private void enableNotification(BluetoothGatt g) {
        if (notifyChar == null) {
            log("警告：没有 FF01 通知特征，只能写不能收，设备不会回应");
            ready = true;
            listener.onReady();
            return;
        }
        int props = notifyChar.getProperties();
        boolean canNotify = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
        boolean canIndicate = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
        if (!canNotify && !canIndicate) {
            log("警告：FF01 既不支持 notify 也不支持 indicate");
        }

        if (!g.setCharacteristicNotification(notifyChar, true)) {
            log("警告：setCharacteristicNotification 返回 false");
        }

        BluetoothGattDescriptor descriptor = notifyChar.getDescriptor(CCCD);
        if (descriptor == null) {
            // 少数设备只在特征列表里暴露 CCCD，逐个找一遍
            for (BluetoothGattDescriptor d : notifyChar.getDescriptors()) {
                if (d.getUuid().equals(CCCD)) {
                    descriptor = d;
                    break;
                }
            }
        }
        if (descriptor == null) {
            log("警告：FF01 上没有找到 CCCD(0x2902)，无法订阅通知 —— 设备响应将收不到");
            ready = true;
            listener.onReady();
            return;
        }
        descriptor.setValue(canNotify
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
        if (!g.writeDescriptor(descriptor)) {
            listener.onFailure("CCCD 写入请求失败");
        }
    }

    // ------------------------------------------------------------------ 发送

    /** 组帧并发送一条命令。查询/结束/提取记录用 {@code data="00"}。 */
    public void send(String cmd, String data) {
        writeFrame(BleFrame.build(cmd, data));
    }

    public void writeFrame(String frame) {
        List<byte[]> chunks = chunkFrame(frame);
        log("发送 " + frame + "（" + frame.length() + " 字符，分 " + chunks.size() + " 片）");
        synchronized (writeQueue) {
            writeQueue.addAll(chunks);
        }
        writeNext();
    }

    /**
     * 分片，复刻 app-service.js:71728 的 {@code lanya8}。
     *
     * <p>关键规则：若某一片的末尾恰好是 "16"（帧结束符），则少切 1 个字符，
     * 保证 "16" 不会被拆开。
     */
    private List<byte[]> chunkFrame(String frame) {
        List<byte[]> out = new ArrayList<>();
        if (frame.length() <= chunkSize) {
            out.add(frame.getBytes(StandardCharsets.US_ASCII));
            return out;
        }
        int i = 0;
        while (i < frame.length()) {
            int end = Math.min(i + chunkSize, frame.length());
            String piece = frame.substring(i, end);
            if (piece.endsWith("16")) {
                int end2 = Math.min(i + chunkSize - 1, frame.length());
                piece = frame.substring(i, end2);
                i += chunkSize - 1;
            } else {
                i += chunkSize;
            }
            if (piece.isEmpty()) {
                break;
            }
            out.add(piece.getBytes(StandardCharsets.US_ASCII));
        }
        return out;
    }

    @SuppressLint("MissingPermission")
    private void writeNext() {
        byte[] next;
        synchronized (writeQueue) {
            if (writing) {
                return;
            }
            next = writeQueue.peek();
            if (next == null) {
                return;
            }
            writing = true;
        }
        if (gatt == null || writeChar == null) {
            writing = false;
            listener.onFailure("蓝牙未就绪");
            return;
        }
        writeChar.setWriteType(writeType);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            int rc = gatt.writeCharacteristic(writeChar, next, writeType);
            if (rc != BluetoothStatusCodesSuccess()) {
                writing = false;
                log("writeCharacteristic 返回 " + rc);
                listener.onFailure("写入请求被拒绝（" + rc + "）");
                return;
            }
        } else {
            writeChar.setValue(next);
            if (!gatt.writeCharacteristic(writeChar)) {
                writing = false;
                log("writeCharacteristic 返回 false");
                listener.onFailure("写入请求被拒绝");
                return;
            }
        }
        synchronized (writeQueue) {
            writeQueue.poll();   // 只有真正发出去才出队
        }
    }

    private static int BluetoothStatusCodesSuccess() {
        return 0;   // BluetoothStatusCodes.SUCCESS
    }

    // ------------------------------------------------------------------ 接收

    /**
     * 粘包重组（app-service.js:71762）。
     *
     * <p>判定一帧结束：末尾是 \n 或 \r，或者（长度 ≥ 15 且以 "#" 开头且以 "16" 结尾）。
     * 随后用校验和二次确认；不通过则继续等后续分片。
     */
    private void appendAndDispatch(byte[] value) {
        String chunk = new String(value, StandardCharsets.US_ASCII);
        log("收到原始数据(" + value.length + "B)：" + toHex(value) + " | " + chunk.trim());
        rxBuffer.append(chunk);
        String s = rxBuffer.toString();
        if (s.isEmpty()) {
            return;
        }
        char last = s.charAt(s.length() - 1);
        boolean boundary = last == '\n' || last == '\r'
                || (s.length() >= 15 && s.charAt(0) == '#' && s.endsWith("16"));
        if (!boundary) {
            return;
        }
        BleFrame.Parsed parsed = BleFrame.parse(s);
        if (parsed == null) {
            log("帧校验未通过，继续等待后续分片：" + s);
            return;
        }
        rxBuffer.setLength(0);
        listener.onFrame(parsed);
    }

    // ------------------------------------------------------------------ 清理

    @SuppressLint("MissingPermission")
    public void close() {
        closed = true;
        ready = false;
        handler.removeCallbacks(connectTimeout);
        stopScan();
        closeGatt();
    }

    @SuppressLint("MissingPermission")
    private void closeGatt() {
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {
            }
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
        writeChar = null;
        notifyChar = null;
        ready = false;
        synchronized (writeQueue) {
            writeQueue.clear();
        }
        writing = false;
        rxBuffer.setLength(0);
    }

    // ------------------------------------------------------------------ 工具

    private void log(String message) {
        Log.i(TAG, message);
        listener.onLog(message);
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format(Locale.US, "%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    private static String describeWriteType(int type) {
        return type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ? "WRITE_TYPE_NO_RESPONSE（无响应）" : "WRITE_TYPE_DEFAULT（带响应）";
    }

    private static String describeProperties(int p) {
        StringBuilder sb = new StringBuilder();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
            sb.append("READ ");
        }
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            sb.append("WRITE ");
        }
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            sb.append("WRITE_NO_RESPONSE ");
        }
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            sb.append("NOTIFY ");
        }
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            sb.append("INDICATE ");
        }
        return sb.toString().trim();
    }
}
