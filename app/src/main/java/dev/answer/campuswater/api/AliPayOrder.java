package dev.answer.campuswater.api;

/**
 * 充值下单返回体。
 *
 * <p>支付宝小程序的充值页对返回值做了分支判断
 * （{@code xaiochen/splitchunk~2/index.worker.js}）：
 * <pre>
 *   var o = /app_id/.test(e.data);
 *   o ? my.tradePay({ orderStr: e.data })    // 完整订单串
 *     : my.tradePay({ tradeNO: e.data });    // 只有交易号
 * </pre>
 * 两种形态必须都支持，否则服务端哪天换成 tradeNO 就会直接失败。
 */
public class AliPayOrder {

    /** 完整订单串（含 app_id=…&biz_content=…&sign=…）。 */
    public final String orderStr;
    /** 仅交易号（P0124… 形式）。 */
    public final String tradeNo;

    private AliPayOrder(String orderStr, String tradeNo) {
        this.orderStr = orderStr;
        this.tradeNo = tradeNo;
    }

    public boolean isOrderStr() {
        return orderStr != null && !orderStr.isEmpty();
    }

    public boolean isTradeNo() {
        return tradeNo != null && !tradeNo.isEmpty();
    }

    /** 按小程序的规则解析：含 {@code app_id} 视为订单串，否则视为交易号。 */
    public static AliPayOrder parse(String data) {
        if (data == null || data.isEmpty()) {
            return new AliPayOrder("", "");
        }
        if (data.contains("app_id")) {
            return new AliPayOrder(data, "");
        }
        return new AliPayOrder("", data);
    }

    @Override
    public String toString() {
        return isOrderStr() ? "orderStr(" + orderStr.length() + " chars)"
                : "tradeNo=" + tradeNo;
    }
}
