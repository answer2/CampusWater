package dev.answer.campuswater.api;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 登录态与账号信息。
 *
 * <p>对应小程序的 {@code globalData.myInfo} + {@code wx.setStorageSync("loginData")}。
 *
 * <p><b>loginCode 是本系统唯一的凭据</b>：它既是会话令牌（每个请求都带），
 * 又是下单签名里的密钥（app-service.js:71479 的 {@code "&key=" + loginCode}）。
 */
public final class Session {

    private static final String PREF = "campuswater_session";
    private static Session instance;

    public String loginCode = "";
    public String telephone = "";
    public String userId = "";
    public String accountId = "";
    public String projectId = "";
    public String accountMoney = "0.00";
    /** 客户端身份（phoneSystem）。部分项目只允许支付宝端接入。 */
    public String platform = ApiConfig.DEFAULT_CLIENT_PLATFORM;

    private SharedPreferences prefs;

    private Session(SharedPreferences prefs) {
        this.prefs = prefs;
        this.loginCode = prefs.getString("loginCode", "");
        this.telephone = prefs.getString("telephone", "");
        this.userId = prefs.getString("userId", "");
        this.accountId = prefs.getString("accountId", "");
        this.projectId = prefs.getString("projectId", "");
        this.accountMoney = prefs.getString("accountMoney", "0.00");
        this.platform = prefs.getString("platform", ApiConfig.DEFAULT_CLIENT_PLATFORM);
    }

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new Session(context.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE));
        }
    }

    public static Session get() {
        return instance;
    }

    public boolean isLoggedIn() {
        return loginCode != null && !loginCode.isEmpty();
    }

    public void save() {
        prefs.edit()
                .putString("loginCode", loginCode)
                .putString("telephone", telephone)
                .putString("userId", userId)
                .putString("accountId", accountId)
                .putString("projectId", projectId)
                .putString("accountMoney", accountMoney)
                .putString("platform", platform)
                .apply();
    }

    public void clear() {
        loginCode = "";
        userId = "";
        accountId = "";
        projectId = "";
        accountMoney = "0.00";
        // 保留用户选择的客户端身份，否则登出后重启会回到默认值
        prefs.edit().clear().putString("platform", platform).apply();
    }
}
