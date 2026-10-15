package dev.answer.campuswater.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条账单。
 *
 * <p>来自 {@code GET /order/query/account/bill/list} 的
 * {@code data[].consumeBillDTO}：
 * <pre>
 * { "orderId":"9564403",                 ← 7 位账单序号
 *   "orderNo":"13200000000000000002",    ← 20 位订单号，和 orderId 不是一回事
 *   "consumeDate":"2026-10-07 03:12:45",
 *   "consumeMoney":"0.41",               ← 字符串、单位元
 *   "description":"公寓洗澡:3号楼南320房" }
 * </pre>
 *
 * <p>注意 {@code consumeMoney} 在这里是<b>字符串元</b>，而
 * {@code consumeOrder/result/query} 与设备上报里的同名字段是<b>数字厘</b>，
 * 两者差 1000 倍，别混用（见 {@link Money}）。
 */
public class BillRecord {

    public String orderId = "";
    public String orderNo = "";
    public String consumeDate = "";
    public String description = "";
    /** 消费金额（元）。 */
    public double money;

    public static List<BillRecord> parse(JSONArray arr) {
        List<BillRecord> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONObject dto = item.optJSONObject("consumeBillDTO");
            if (dto == null) {
                dto = item;
            }
            BillRecord b = new BillRecord();
            b.orderId = dto.optString("orderId", "");
            b.orderNo = dto.optString("orderNo", "");
            b.consumeDate = dto.optString("consumeDate", "");
            b.description = dto.optString("description", "");
            b.money = dto.optDouble("consumeMoney", 0d);
            out.add(b);
        }
        return out;
    }

    /**
     * 设备名。
     *
     * <p>{@code description} 一般是"类型:名称"，而名称本身常长成
     * {@code 热水表-朴苑4栋昭通学院热水器-2层-232房} —— 开头一个"热水表"、中间又一个"热水器"，
     * 一行放不下还重复。这里把类型词与"××表"前缀去掉，只留地点。
     */
    public String deviceName() {
        if (description == null || description.isEmpty()) {
            return "未知设备";
        }
        int idx = description.indexOf(':');
        if (idx < 0) {
            idx = description.indexOf('：');
        }
        String raw = (idx >= 0 ? description.substring(idx + 1) : description).trim();
        String type = typeLabel();
        StringBuilder out = new StringBuilder();
        for (String part : raw.split("-")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            p = p.replace(type, "").trim();
            // 只剩个设备前缀（"热水表"、"饮水表"）时丢掉
            if (p.isEmpty() || (p.length() <= 3 && p.endsWith("表"))) {
                continue;
            }
            if (out.length() > 0) {
                out.append('-');
            }
            out.append(p);
        }
        return out.length() == 0 ? raw : out.toString();
    }

    /** 设备类型标签，用来决定这一行的着色。 */
    public String typeLabel() {
        String d = description == null ? "" : description;
        if (d.contains("饮水") || d.contains("直饮") || d.contains("冷水")) {
            return "饮水机";
        }
        if (d.contains("洗手台")) {
            return "洗手台";
        }
        if (d.contains("吹风")) {
            return "吹风机";
        }
        if (d.contains("洗衣")) {
            return "洗衣机";
        }
        if (d.contains("充电")) {
            return "充电";
        }
        if (d.contains("洗澡") || d.contains("淋浴") || d.contains("热水")) {
            return "热水器";
        }
        return d.contains(":") ? d.substring(0, d.indexOf(':')) : "消费";
    }

    /** 只显示"月-日 时:分"，年份在标题上已经有了。 */
    public String shortTime() {
        String d = consumeDate == null ? "" : consumeDate;
        String digits = d.replaceAll("[^0-9]", "");
        if (digits.length() >= 16) {
            return digits.substring(4, 6) + "-" + digits.substring(6, 8)
                    + " " + digits.substring(8, 10) + ":" + digits.substring(10, 12);
        }
        return d.length() > 16 ? d.substring(5, 16) : d;
    }
}
