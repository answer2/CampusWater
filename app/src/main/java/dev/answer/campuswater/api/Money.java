package dev.answer.campuswater.api;

import org.json.JSONObject;

/**
 * 金额单位换算。
 *
 * <p>趣智校园在服务端把金额存成「厘」（1 元 = 1000），但同一个逻辑字段在不同接口里
 * 的形态不一样，实测结论：
 * <ul>
 *   <li>账号余额 {@code accountRealMoney} 等：<b>整数</b>厘 → {@code /1000}
 *       （见 {@link WaterApi} 里 applyAccount 的写法）</li>
 *   <li>{@code consumeOrder/result/query} 的 {@code consumeMoney}：<b>数字</b> 40 表示 0.04 元</li>
 *   <li>{@code query/account/bill/list} 的 {@code consumeMoney}：<b>字符串</b> {@code "0.04"}，已经是元</li>
 * </ul>
 *
 * <p>所以这里的规则是：<b>JSON 数字一律按厘处理，字符串按元处理</b>。
 */
public final class Money {

    private Money() {
    }

    /** 数字型字段：厘 → 元。 */
    public static double milliToYuan(long milli) {
        return milli / 1000.0;
    }

    /** 解析一个可能是厘（数字）或元（字符串）的字段；拿不到返回 {@code null}。 */
    public static Double parse(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof Number) {
            return milliToYuan(((Number) value).longValue());
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 服务端余额常带三位小数（厘精度，如 "24.808"），界面只该看到两位。
     *
     * <p>解析不出来时原样返回，别把未知值变成 0。
     */
    public static String display(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "0.00";
        }
        try {
            return String.format(java.util.Locale.US, "%.2f", Double.parseDouble(raw.trim()));
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /** 展示成 ¥12.34。 */
    public static String format(double yuan) {
        return String.format(java.util.Locale.US, "¥%.2f", yuan);
    }

    /** {@code null} 显示成"结算中"，别显示成 ¥0.00 —— 那是两个完全不同的结论。 */
    public static String formatOrNull(Double yuan) {
        return yuan == null ? "结算中" : format(yuan);
    }
}
