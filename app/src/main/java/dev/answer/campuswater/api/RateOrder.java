package dev.answer.campuswater.api;

import org.json.JSONObject;

/**
 * {@code .../bluetooth/rateOrder} 下单响应的 {@code data}。
 *
 * <p>以前只取 {@code downData}，把同一个响应里的另外两个关键字段扔掉了：
 * <ul>
 *   <li>{@code autoDisConTime} —— 闲置自动关停时长，<b>单位是秒</b>
 *       （小程序把它连同费率一起存进 downListShower/downListDrinkIng，见 app-service.js:71513）</li>
 *   <li>{@code consumeDate} —— 本单的账单时间戳串，开阀失败时要拿它上报
 *       {@code /order/upload/bluetooth/fail}</li>
 * </ul>
 */
public class RateOrder {

    public String downData = "";
    public String consumeDate = "";
    public String orderNo = "";
    /** 闲置自动关停倒计时秒数；{@code <= 0} 表示服务端没给（部分项目不下发）。 */
    public int autoDisConTime;

    public static RateOrder fromJson(JSONObject data) {
        RateOrder r = new RateOrder();
        if (data == null) {
            return r;
        }
        r.downData = data.optString("downData", "");
        r.consumeDate = data.optString("consumeDate", "");
        r.orderNo = data.optString("orderNo", "");
        r.autoDisConTime = data.optInt("autoDisConTime", 0);
        return r;
    }

    public boolean hasCountdown() {
        return autoDisConTime > 0;
    }
}
