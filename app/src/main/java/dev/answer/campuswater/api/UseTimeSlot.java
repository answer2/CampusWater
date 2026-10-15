package dev.answer.campuswater.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备的使用时段（供水时段）。
 *
 * <p>服务端返回 {@code data: [{startTime:"06:00", stopTime:"08:00"}, …]}
 * （app-service.js:43989 的 {@code moduleSupplyTime}）。
 *
 * <p>「当前是否在时段内」的判定照搬小程序 {@code commenUseTimeFn}
 * （app-service.js:52811-52823）：
 * <pre>
 *   var c = n &gt; r;                  // start &gt; stop 即为跨天时段
 *   if (c) { n = stop; r = start; } // 交换两端
 *   return i &gt; n &amp;&amp; i &lt; r ? !c : !!c;
 * </pre>
 * 不跨天时就是 {@code start &lt; now &lt; stop}；
 * 跨天时（如 22:00–06:00）反过来判断"是否落在白天那段空档里"。
 *
 * <p>{@code java.time} 需要 API 26，本项目 minSdk 正好是 26。
 */
public class UseTimeSlot {

    public String startTime = "";
    public String stopTime = "";

    /** 把 "HH:mm"（兼容 "HH:mm:ss"）解析成当天分钟数；解析失败返回 -1。 */
    private static int minutes(String hhmm) {
        if (hhmm == null) {
            return -1;
        }
        String[] parts = hhmm.trim().split(":");
        if (parts.length < 2) {
            return -1;
        }
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            if (h < 0 || h > 24 || m < 0 || m > 59) {
                return -1;
            }
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public String label() {
        return startTime + "-" + stopTime;
    }

    /** 当前时间是否落在这个时段内。 */
    public boolean containsNow() {
        int start = minutes(startTime);
        int stop = minutes(stopTime);
        if (start < 0 || stop < 0) {
            return false;
        }
        LocalTime now = LocalTime.now();
        int cur = now.getHour() * 60 + now.getMinute();

        boolean crossMidnight = start > stop;
        if (!crossMidnight) {
            return cur > start && cur < stop;
        }
        // 跨天：只有落进 [stop, start] 这段空档才算"不在时段"
        boolean inGap = cur > stop && cur < start;
        return !inGap;
    }

    // ------------------------------------------------------------------ 解析 / 缓存

    private static final Map<Integer, List<UseTimeSlot>> CACHE = new HashMap<>();

    /** 取缓存的时段；没查过返回 null（注意与"查过但为空"区分）。 */
    public static List<UseTimeSlot> cached(int moduleId) {
        synchronized (CACHE) {
            return CACHE.get(moduleId);
        }
    }

    public static void cache(int moduleId, List<UseTimeSlot> slots) {
        synchronized (CACHE) {
            CACHE.put(moduleId, slots);
        }
    }

    /** 从接口返回体解析时段列表。 */
    public static List<UseTimeSlot> parse(JSONArray arr) {
        List<UseTimeSlot> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            UseTimeSlot s = new UseTimeSlot();
            s.startTime = o.optString("startTime", "");
            s.stopTime = o.optString("stopTime", "");
            if (!s.startTime.isEmpty() && !s.stopTime.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 展示

    /** "06:00-08:00 / 11:30-13:30" */
    public static String summary(List<UseTimeSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (UseTimeSlot s : slots) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(s.label());
        }
        return sb.toString();
    }

    /** 当前是否在任一时段内。列表为空视为"未配置限制"，返回 true。 */
    public static boolean isNowAllowed(List<UseTimeSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return true;
        }
        for (UseTimeSlot s : slots) {
            if (s.containsNow()) {
                return true;
            }
        }
        return false;
    }

    /** 当前命中的时段；没有则返回 null。 */
    public static UseTimeSlot current(List<UseTimeSlot> slots) {
        if (slots == null) {
            return null;
        }
        for (UseTimeSlot s : slots) {
            if (s.containsNow()) {
                return s;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return label();
    }
}
