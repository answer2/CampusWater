package dev.answer.campuswater.ble;

/**
 * 设备私有协议的组帧与解析。
 *
 * <p>帧格式（<b>纯 ASCII 文本</b>，不是二进制）：
 * <pre>
 *   #60 | LEN(4) | CTRL(2) | CMD(2) | RSV(2) | DATA(2n) | CHK(2) | 16
 *   LEN = 3 + DATA 字节数
 *   CHK = (CTRL + CMD + RSV + ΣDATA) &amp; 0xFF
 * </pre>
 *
 * <p>对应 app-service.js 的 {@code queryDevice}(71803)、{@code downRate}(71789)、
 * {@code ClearBLE}(72006)、{@code analysisSwitch}(71251) 与 {@code checkBleGet}(71923)。
 *
 * <p><b>发送方式</b>：小程序写的是 {@code wx.base64ToArrayBuffer(btoa(frame))}，
 * 这只是"字符串转 ArrayBuffer"的惯用手法，实际发出的是 ASCII 字节。
 * 因此本类直接返回 String，由 {@link BleClient} 用 US_ASCII 编码写入。
 */
public final class BleFrame {

    /** 设备广播名（app-service.js:72081）。 */
    public static final String DEVICE_NAME = "KLCXKJ-Water";

    /** 服务 UUID 片段（app-service.js:71618）。 */
    public static final String SERVICE_HINT = "FF00";
    /** 通知特征 UUID 片段（app-service.js:71655）。 */
    public static final String NOTIFY_HINT = "FF01";
    /** 写特征 UUID 片段；缺失即判定失败（app-service.js:71657）。 */
    public static final String WRITE_HINT = "FF02";

    /** 通知特征的兜底 UUID（app-service.js:71660）。 */
    public static final String NOTIFY_FALLBACK = "0000FF01-0000-1000-8000-00805F9B34FB";
    /** 写特征的兜底 UUID（app-service.js:71662）。 */
    public static final String WRITE_FALLBACK = "0000FF02-0000-1000-8000-00805F9B34FB";

    // 命令字（app-service.js:71938 的分发表）
    public static final String CMD_QUERY = "23";     // 查询设备状态
    public static final String CMD_DOWN_RATE = "21"; // 下发费率（真正出水）
    public static final String CMD_END = "22";       // 结束使用
    public static final String CMD_RECORD = "85";    // 提取消费记录
    public static final String CMD_CLEAR = "86";     // 清零
    public static final String CMD_SWITCH = "88";    // 启用/禁用

    /** 成功标志：所有响应的 DATA 首字节（app-service.js:71818 等）。 */
    public static final String OK = "80";

    private BleFrame() {
    }

    /**
     * 组帧。查询/结束/提取记录这三个命令的 DATA 固定为 "00"。
     *
     * @param ctrl 控制域，通常 "80"
     * @param cmd  命令字，如 "23"
     * @param rsv  保留域，通常 "00"
     * @param data 数据域十六进制串，可为空
     */
    public static String build(String ctrl, String cmd, String rsv, String data) {
        String d = data == null ? "" : data;
        int len = 3 + d.length() / 2;
        int sum = Integer.parseInt(ctrl, 16)
                + Integer.parseInt(cmd, 16)
                + Integer.parseInt(rsv, 16);
        for (int i = 0; i + 1 < d.length(); i += 2) {
            sum += Integer.parseInt(d.substring(i, i + 2), 16);
        }
        return "#60" + pad4(Integer.toHexString(len)) + ctrl + cmd + rsv + d
                + last2(Integer.toHexString(sum)) + "16";
    }

    /** 组帧的常用形式：CTRL=80、RSV=00。 */
    public static String build(String cmd, String data) {
        return build("80", cmd, "00", data);
    }

    /**
     * 解析并校验一帧。
     *
     * <p><b>按 LEN 推导 DATA 长度</b>，而不是像小程序那样从尾部固定偏移取。
     * 小程序的 {@code checkBleGet} 用的是 {@code slice(13,-5)} / {@code slice(-5,-3)}，
     * 这隐含假设"帧尾还有一个换行符"；一旦换行符不在同一个 BLE 包里到达
     * （Android 上很常见），CHK 就会错位一个 nibble，导致合法帧被判为校验失败。
     *
     * <p>实测设备应答（长度 115）：
     * <pre>
     *   #60 0034 81 23 00 | 49 字节 DATA | B9 | 16
     *   LEN=0x34=52 → DATA = 52-3 = 49 字节 = 98 个十六进制字符
     *   13 + 98 + 2(CHK) + 2("16") = 115 ✔
     * </pre>
     * 这里优先用 LEN 布局，失败再退回小程序的尾部偏移布局以保持兼容。
     *
     * @return 校验通过返回 {@link Parsed}；否则返回 null（应继续等待后续分片）
     */
    public static Parsed parse(String frame) {
        if (frame == null) {
            return null;
        }
        String f = frame.trim();
        // 去掉可能附在帧尾的 CR / LF
        while (f.length() > 0) {
            char last = f.charAt(f.length() - 1);
            if (last == '\n' || last == '\r') {
                f = f.substring(0, f.length() - 1);
            } else {
                break;
            }
        }
        if (f.length() < 19 || !f.startsWith("#60") || !f.endsWith("16")) {
            return null;
        }
        String ctrl = f.substring(7, 9);
        String cmd = f.substring(9, 11);
        String rsv = f.substring(11, 13);

        // 布局一（首选）：DATA 长度由 LEN 决定
        try {
            int len = Integer.parseInt(f.substring(3, 7), 16);
            int dataChars = (len - 3) * 2;
            if (dataChars > 0 && 13 + dataChars + 4 == f.length()) {
                Parsed p = validate(f, ctrl, cmd, rsv, 13 + dataChars);
                if (p != null) {
                    return p;
                }
            }
        } catch (NumberFormatException ignored) {
            // 落到下面的兼容布局
        }

        // 布局二（兼容小程序的 checkBleGet）
        return validate(f, ctrl, cmd, rsv, f.length() - 5);
    }

    /** 用给定的 DATA 结束下标校验一帧。 */
    private static Parsed validate(String f, String ctrl, String cmd, String rsv, int dataEnd) {
        if (dataEnd < 13 || dataEnd + 4 > f.length()) {
            return null;
        }
        String data = f.substring(13, dataEnd);
        if ((data.length() & 1) != 0) {
            return null;
        }
        String chk = f.substring(dataEnd, dataEnd + 2).toUpperCase();
        int sum;
        try {
            sum = Integer.parseInt(ctrl, 16)
                    + Integer.parseInt(cmd, 16)
                    + Integer.parseInt(rsv, 16);
            for (int i = 0; i + 1 < data.length(); i += 2) {
                sum += Integer.parseInt(data.substring(i, i + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (!last2(Integer.toHexString(sum)).equalsIgnoreCase(chk)) {
            return null;
        }
        return new Parsed(ctrl, cmd, rsv, data);
    }

    /** 一帧的四个域。 */
    public static final class Parsed {
        public final String ctrl;
        public final String cmd;
        public final String rsv;
        public final String data;

        Parsed(String ctrl, String cmd, String rsv, String data) {
            this.ctrl = ctrl;
            this.cmd = cmd;
            this.rsv = rsv;
            this.data = data;
        }

        /** DATA 首字节是否表示成功（"80"）。 */
        public boolean isOk() {
            return data != null && data.length() >= 2
                    && OK.equalsIgnoreCase(data.substring(0, 2));
        }

        @Override
        public String toString() {
            return "cmd=" + cmd + " data=" + data;
        }
    }

    private static String pad4(String hex) {
        StringBuilder sb = new StringBuilder(hex);
        while (sb.length() < 4) {
            sb.insert(0, '0');
        }
        return sb.length() > 4 ? sb.substring(sb.length() - 4) : sb.toString();
    }

    private static String last2(String hex) {
        return hex.length() <= 2 ? hex : hex.substring(hex.length() - 2);
    }
}
