package dev.answer.campuswater.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 充值面额。
 *
 * <p>来源 {@code GET /settlement/recharge/package/list}：
 * <pre>
 * { "typeId": 10993, "projectId": 124, "amount": 10.0,
 *   "givenMoney": 0.0, "activityId": null }
 * </pre>
 * {@code givenMoney} 是赠送金额，界面展示成"充 50 送 5"。
 */
public class RechargeOption {

    public int typeId;
    public double amount;
    public double givenMoney;

    /** 实际到账 = 充值金额 + 赠送金额。 */
    public double credited() {
        return amount + givenMoney;
    }

    public boolean hasGift() {
        return givenMoney > 0;
    }

    public String amountLabel() {
        return String.format(Locale.US, "¥%.0f", amount);
    }

    public String detailLabel() {
        if (hasGift()) {
            return String.format(Locale.US, "实得 ¥%.2f", credited());
        }
        return String.format(Locale.US, "到账 ¥%.2f", credited());
    }

    public static List<RechargeOption> parse(JSONArray arr) {
        List<RechargeOption> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            RechargeOption opt = new RechargeOption();
            opt.typeId = o.optInt("typeId", 0);
            opt.amount = o.optDouble("amount", 0);
            opt.givenMoney = o.optDouble("givenMoney", 0);
            if (opt.typeId != 0 && opt.amount > 0) {
                out.add(opt);
            }
        }
        return out;
    }
}
