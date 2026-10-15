package dev.answer.campuswater.ble;

/**
 * 设备 {@code 0x23} 状态查询响应的解析结果。
 *
 * <p>对应 app-service.js:71813 的 {@code analysisQuery}。
 * DATA 去掉首字节 "80" 之后的布局，<b>长帧与短帧不同</b>：
 *
 * <pre>
 * 偏移   长帧(len&gt;56)      短帧
 * 18     accID(4B)         accID(4B)
 * 26     随机数(6B)         随机数(6B)
 * 38     CommMode(1B)       状态(1B)
 * 42     —                  tactimes(2B)
 * 46     —                  macType(2B)
 * 48     tactimes(4B)       —
 * 56     状态(1B)           —
 * 58     macType(2B)        —
 * </pre>
 */
public class DeviceStatus {

    /** 设备空闲。 */
    public static final String STATE_IDLE = "00";
    /** 使用中。 */
    public static final String STATE_IN_USE = "01";
    /** 刷卡消费中。 */
    public static final String STATE_CARD = "02";

    public boolean success;
    /** 设备上记录的账号 ID，用于判断"是不是我在用"。 */
    public long accId;
    /** 6 字节随机数；下单时作为 macAddress 字段上传（不是真实 MAC）。 */
    public String randomNumber = "";
    /** 传给服务端的 randomNumber（设备侧叫 tactimes）。 */
    public String tactimes = "";
    /** 通讯方式，下单时作为 protocolType 上传。 */
    public String commMode = "";
    /** 设备状态：00 空闲 / 01 使用中 / 02 刷卡中 / 其他。 */
    public String state = "";
    public String macType = "";

    public boolean isIdle() {
        return STATE_IDLE.equals(state);
    }

    public boolean isInUse() {
        return STATE_IN_USE.equals(state);
    }

    public boolean isCardConsuming() {
        return STATE_CARD.equals(state);
    }

    /** 是否需要走 0x85 提取消费记录。 */
    public boolean needsRecordFetch() {
        return !isIdle() && !isInUse() && !isCardConsuming();
    }

    public static DeviceStatus parse(String data) {
        DeviceStatus st = new DeviceStatus();
        if (data == null || data.length() < 40) {
            return st;
        }
        st.success = data.regionMatches(true, 0, BleFrame.OK, 0, 2);
        if (!st.success) {
            return st;
        }
        try {
            st.accId = Long.parseLong(data.substring(18, 26), 16);
            st.randomNumber = data.substring(26, 38);

            if (data.length() > 56) {
                st.commMode = data.substring(38, 40);
                st.tactimes = data.substring(48, 56);
                st.state = data.substring(56, 58);
                st.macType = data.substring(58, 62);
            } else {
                st.state = data.substring(38, 40);
                st.tactimes = data.substring(42, 46);
                st.macType = data.substring(46, 50);
            }
        } catch (Exception ignored) {
            st.success = false;
        }
        return st;
    }

    @Override
    public String toString() {
        return "state=" + state + " accId=" + accId
                + " tactimes=" + tactimes + " CommMode=" + commMode;
    }
}
