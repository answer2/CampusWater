package dev.answer.campuswater.api;

import org.json.JSONObject;

/**
 * 一单的结算结果。
 *
 * <p>蓝牙表的结算不走 {@code consumeOrder/result/query}（那个接口要 snCode + orderNo，
 * 是给联网设备用的），而是直接读 {@code /order/upload/bluetooth/data} 的返回：
 * {@code data} 里带 {@code consumeMoney / preDeductMoney / preDeductMoneyAfter / consumeTime}
 * （见 app-service.js:42257，小程序拿到后原样丢给"消费完成"页）。
 *
 * <p>{@code money} 可能是 {@code null}：服务端先回占位、金额随后才填，
 * 这种情况要用账单列表补查，不能当成"这一单没花钱"。
 */
public class Settlement {

    /** 本单实际消费（元）；{@code null} = 还没结算出来。 */
    public Double money;
    /** 服务端预扣金额（元），用于"金额还没出但能看到预扣"的兜底展示。 */
    public Double preDeduct;
    /** 账单时间串，形如 {@code 20261007031245}。 */
    public String consumeTime = "";
    /** 用水时长（毫秒）由客户端自己算：关阀时刻 - 开始用水时刻。 */
    public long durationMs;
    /** 金额来源，便于在日志里说清楚这个数是从哪来的。 */
    public String source = "";

    public boolean hasMoney() {
        return money != null;
    }

    public static Settlement fromUploadData(JSONObject data) {
        Settlement s = new Settlement();
        if (data == null) {
            return s;
        }
        s.money = Money.parse(data.opt("consumeMoney"));
        s.preDeduct = Money.parse(data.opt("preDeductMoney"));
        s.consumeTime = data.optString("consumeTime", "");
        s.source = s.money != null ? "设备上报结算" : "金额未出";
        return s;
    }

    /** 时长显示成"3 分 12 秒"。 */
    public static String formatDuration(long ms) {
        if (ms <= 0) {
            return "0 秒";
        }
        long sec = Math.round(ms / 1000.0);
        long min = sec / 60;
        if (min <= 0) {
            return sec + " 秒";
        }
        long rest = sec % 60;
        if (min < 60) {
            return rest == 0 ? (min + " 分钟") : (min + " 分 " + rest + " 秒");
        }
        return (min / 60) + " 小时 " + (min % 60) + " 分";
    }
}
