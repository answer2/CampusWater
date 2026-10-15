package dev.answer.campuswater.api;

/** 业务错误：HTTP 200 但 errorCode != 0。 */
public class ApiException extends Exception {

    public final int errorCode;
    public final String errorMessage;

    public ApiException(int errorCode, String errorMessage) {
        super("errorCode=" + errorCode + " : " + errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    /** 账户被禁用（app-service.js:52670）。 */
    public boolean isAccountDisabled() {
        return errorCode == 23;
    }

    /** 余额不足，需要充值。 */
    public boolean isNotEnoughBalance() {
        return errorCode == 204;
    }

    /** 需要先绑定学校/项目。 */
    public boolean isNeedBindProject() {
        return errorCode == 2;
    }

    /**
     * 登录态已失效，需要重新登录。
     *
     * <p>小程序对这两个码的处理是"清空本地存储并跳登录页"
     * （app-service.js:53492 的 {@code 8 == errorCode} 与 53499 的 {@code 1e4 == errorCode}）。
     */
    public boolean isSessionExpired() {
        return errorCode == 8 || errorCode == 10000;
    }

    /** 项目不允许当前客户端接入。 */
    public boolean isClientNotAllowed() {
        String m = errorMessage == null ? "" : errorMessage;
        return m.contains("不支持该应用") || m.contains("不支持此应用")
                || (m.contains("请使用") && m.contains("小程序"));
    }
}
