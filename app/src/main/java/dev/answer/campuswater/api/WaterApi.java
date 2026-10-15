package dev.answer.campuswater.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 业务接口封装。<b>所有方法都必须在后台线程调用。</b>
 *
 * <p>调用链与小程序一致：
 * <pre>
 *   短信登录(/user/registerAndLogin, type=5)  →  拿到 loginCode
 *     → GET /user/info                       →  拿到 accountId、余额，并切换项目
 *       → GET /device/list/mac               →  拿到设备档案
 *         → POST .../bluetooth/rateOrder     →  拿到 downData（下发给设备才出水）
 * </pre>
 */
public final class WaterApi {

    private WaterApi() {
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 发送短信验证码（package_operationalGuidelines/app-service.js:9941）。
     *
     * <p>{@code secret} 是客户端算的静态校验串：{@code md5(手机号前3位 + 第7位起 + "klcx")}，
     * 不是短信签名，可原样复现。
     */
    public static void sendVerifyCode(String telephone) throws Exception {
        if (telephone == null || telephone.length() != 11) {
            throw new IllegalArgumentException("请输入 11 位手机号");
        }
        String secret = Md5Util.md5(
                telephone.substring(0, 3) + telephone.substring(7) + "klcx");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("telephone", telephone);
        params.put("secret", secret);
        params.put("typeId", "3");
        params.put("platform", "1");
        Http.checkData(Http.get(ApiConfig.EP_VERIFY_CODE, params));
    }

    /**
     * 短信验证码注册并登录，{@code type=5}（package_operationalGuidelines/app-service.js:10000）。
     *
     * <p>这条路径<b>不需要 openid，也不需要 wx.login 的 code</b>，因此可被原生 App 复现。
     * 成功后写入 {@link Session}。
     */
    public static void registerAndLogin(String telephone, String smsCode) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("telephone", telephone);
        params.put("smsCode", smsCode);
        params.put("type", "5");
        JSONObject data = Http.checkData(Http.post(ApiConfig.EP_REGISTER_AND_LOGIN, params));
        applyLoginData(data, telephone);
    }

    /**
     * 账号密码登录（app-service.js:42452）。
     *
     * <p>口令哈希是 {@code md5(明文)} 取末 10 位（app-service.js:42354），非标准做法但可复现。
     * {@code identifier} 在小程序里是微信 openid，本应用无法提供，故留空——
     * 服务端是否强制校验它需要实测；若强制，请改用短信登录。
     */
    public static void passwordLogin(String telephone, String password) throws Exception {
        String hash = Md5Util.md5(password);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("telephone", telephone);
        params.put("password", hash.substring(hash.length() - 10));
        params.put("type", "1");
        JSONObject data = Http.checkData(Http.post(ApiConfig.EP_LOGIN, params));
        applyLoginData(data, telephone);
    }

    /** 从登录/用户信息返回体里提取会话字段并落盘。 */
    private static void applyLoginData(JSONObject data, String telephone) {
        Session s = Session.get();
        s.loginCode = data.optString("loginCode", s.loginCode);
        s.telephone = data.optString("telephone", telephone);
        s.userId = data.optString("userId", s.userId);
        applyAccount(data);
        s.save();
    }

    /**
     * 提取账号字段。
     *
     * <p>关键点：<b>projectId 挂在 userAccount 上</b>，不在顶层。
     * 小程序全局的 myInfo 就是 userAccount 这个对象
     * （package_operationalGuidelines/app-service.js:9739），
     * 而 setBindProjectInfoV3 也用 {@code c.projectId || e.projectId} 取值
     * （app-service.js:52650），即 userAccount.projectId 优先。
     */
    private static void applyAccount(JSONObject data) {
        Session s = Session.get();
        JSONObject account = data.optJSONObject("userAccount");
        if (account != null) {
            s.accountId = account.optString("accountId", s.accountId);
            int real = account.optInt("accountRealMoney", 0);
            int given = account.optInt("accountGivenMoney", 0);
            s.accountMoney = String.format(java.util.Locale.US, "%.2f", (real + given) / 1000.0);
            String pid = firstNonEmpty(account, "projectId", "PrjID");
            if (!pid.isEmpty() && !"0".equals(pid)) {
                s.projectId = pid;
            }
        }
        String topPid = data.optString("projectId", "");
        if (!topPid.isEmpty() && !"0".equals(topPid)) {
            s.projectId = topPid;
        }
        // 兜底：登录/绑定时服务端可能只回一个已绑定项目列表
        if (s.projectId == null || s.projectId.isEmpty()) {
            JSONArray list = data.optJSONArray("userProjectList");
            if (list != null && list.length() > 0) {
                JSONObject first = list.optJSONObject(0);
                if (first != null) {
                    String pid = firstNonEmpty(first, "projectId", "PrjID");
                    if (!pid.isEmpty() && !"0".equals(pid)) {
                        s.projectId = pid;
                    }
                }
            }
        }
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty() && !"null".equals(v)) {
                return v;
            }
        }
        return "";
    }

    /**
     * 拉取用户信息，用于拿到 {@code accountId} 并把账号绑定到目标项目。
     *
     * <p>对应 app-service.js:52639 的 {@code setBindProjectInfoV3}。
     * 进入设备页时必须调用：设备可能属于另一个校区，需要让服务端把账号切过去。
     *
     * @throws ApiException errorCode 23 表示账户被禁用
     */
    public static void bindProject(String projectId) throws Exception {
        Map<String, String> params = new LinkedHashMap<>();
        if (projectId != null && !projectId.isEmpty()) {
            params.put("projectId", projectId);
        }
        JSONObject data = Http.checkData(Http.get(ApiConfig.EP_USER_INFO, params));
        Session s = Session.get();
        s.loginCode = data.optString("loginCode", s.loginCode);
        s.telephone = data.optString("telephone", s.telephone);
        applyAccount(data);
        // 指定了目标项目就以它为准（设备可能属于另一个校区）
        if (projectId != null && !projectId.isEmpty()) {
            s.projectId = projectId;
        }
        s.save();
    }

    /**
     * 刷新账号信息（不指定项目）。
     *
     * <p>登录后必须先调一次：projectId 与 accountId 都在 {@code userAccount} 里，
     * 而 {@code /user/registerAndLogin} 的返回体不一定带全。
     */
    // ------------------------------------------------------------------ 充值

    /**
     * 查询可用的支付方式。
     *
     * <p>来源：官方 Android App 抓包，{@code GET /settlement/recharge/pay/mode}。
     * 返回 {@code data.payWay}，2 表示支付宝。
     */
    public static List<Integer> queryPayWays() throws Exception {
        Session s = Session.get();
        JSONObject data = Http.checkData(Http.get(ApiConfig.EP_RECHARGE_PAY_MODE, accountParams()));
        List<Integer> ways = new ArrayList<>();
        JSONArray arr = data.optJSONArray("payWay");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                ways.add(arr.optInt(i));
            }
        }
        return ways;
    }

    /** 查询充值面额列表。 */
    public static List<RechargeOption> queryRechargeOptions() throws Exception {
        JSONObject response = Http.get(ApiConfig.EP_RECHARGE_PACKAGE, accountParams());
        int code = response.optInt("errorCode", 0);
        if (code != 0) {
            throw new ApiException(code,
                    response.optString("errorMessage",
                            response.optString("message", "查询充值面额失败")));
        }
        return RechargeOption.parse(response.optJSONArray("data"));
    }

    /**
     * 发起支付宝充值，拿到 App 支付订单串。
     *
     * <p>来源：官方 Android App 抓包，{@code POST /settlement/alipay/recharge/balance}。
     *
     * <p>返回的订单串交给支付宝 SDK 的 {@code PayTask.payV2()} 即可拉起收银台。
     * 支付结果通过服务端的 {@code notify_url} 入账，客户端不需要回调。
     *
     * @param amount 充值金额（元），内部会格式化成一位小数，如 {@code "10.0"}
     * @param payWay 支付方式，支付宝为 {@link ApiConfig#RECHARGE_WAY_ALIPAY}
     * @return 订单串或交易号，见 {@link AliPayOrder}
     */
    public static AliPayOrder createAlipayOrder(double amount, int payWay) throws Exception {
        Session s = Session.get();
        String money = String.format(Locale.US, "%.1f", amount);
        String tel = s.telephone;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("appId", ApiConfig.WECHAT_APP_ID);
        params.put("telPhone", tel);
        params.put("rechargeMoney", money);
        params.put("payWay", String.valueOf(payWay));
        params.put("loginCode", s.loginCode);
        params.put("userId", s.userId);
        params.put("accountId", s.accountId);
        if (s.projectId != null && !s.projectId.isEmpty()) {
            params.put("projectId", s.projectId);
        }
        params.put("signature", rechargeSignature(s.loginCode, payWay, money, tel));

        JSONObject response = Http.post(ApiConfig.EP_RECHARGE_BALANCE, params);
        lastRechargeRaw = response.toString();
        int code = response.optInt("errorCode", 0);
        if (code != 0) {
            throw new ApiException(code,
                    response.optString("errorMessage",
                            response.optString("message", "发起充值失败")));
        }
        String data = response.optString("data", "");
        AliPayOrder order = AliPayOrder.parse(data);
        if (!order.isOrderStr() && !order.isTradeNo()) {
            throw new ApiException(-1, "服务端未返回支付宝订单串");
        }
        return order;
    }

    /** 最近一次充值下单的原始返回，供界面排错展示。 */
    private static volatile String lastRechargeRaw;

    public static String getLastRechargeRaw() {
        return lastRechargeRaw;
    }

    /**
     * 充值签名。
     *
     * <p>规则来自支付宝小程序充值页（{@code xaiochen/splitchunk~2/index.worker.js}）：
     * <pre>
     *   v = "loginCode" + loginCode + "payWay" + payWay
     *     + "rechargeMoney" + rechargeMoney + "telPhone" + telPhone + "sign-kailu=";
     *   signature = md5(v).toLowerCase();
     * </pre>
     *
     * <p><b>金额必须带一位小数</b>（{@code "10.0"} 而非 {@code "10"}），
     * 否则算出来对不上——已实测验证。
     */
    public static String rechargeSignature(String loginCode, int payWay,
                                           String rechargeMoney, String telPhone) {
        String raw = "loginCode" + loginCode
                + "payWay" + payWay
                + "rechargeMoney" + rechargeMoney
                + "telPhone" + telPhone
                + ApiConfig.RECHARGE_SIGN_SUFFIX;
        return Md5Util.md5(raw);
    }

    /** 充值类接口的公共参数，与抓包一致。 */
    private static Map<String, String> accountParams() {
        Session s = Session.get();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("accountId", s.accountId);
        params.put("userId", s.userId);
        params.put("telephone", s.telephone);
        params.put("telPhone", s.telephone);
        params.put("projectId", s.projectId);
        return params;
    }

    /**
     * 查询某个模块的使用时段（app-service.js:43980 的 {@code moduleSupplyTimeV3}）。
     *
     * <p>是 <b>GET</b>：该模块 {@code s = o.httpRequestAgainGet}（43554）。
     *
     * @param moduleId 模块编号，见 {@link ApiConfig#moduleIdFor(int)}
     */
    public static List<UseTimeSlot> queryUseTime(int moduleId) throws Exception {
        Session s = Session.get();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("telephone", s.telephone);
        params.put("moduleId", String.valueOf(moduleId));
        params.put("projectId", s.projectId);
        JSONObject response = Http.get(ApiConfig.EP_MODULE_SUPPLY_TIME, params);
        int code = response.optInt("errorCode", 0);
        if (code != 0) {
            throw new ApiException(code,
                    response.optString("errorMessage",
                            response.optString("message", "查询使用时段失败")));
        }
        List<UseTimeSlot> slots = UseTimeSlot.parse(response.optJSONArray("data"));
        UseTimeSlot.cache(moduleId, slots);
        return slots;
    }

    /**
     * 查询钱包余额（app-service.js:44521 的 {@code getWalletMoneyV3}）。
     *
     * <p><b>注意是 GET</b>：该模块里 {@code o = e.httpRequestAgainGet}（app-service.js:44420），
     * 而 {@code getWalletMoneyV3} 调的是 {@code o(a.Wallet, ...)}（44526）。
     * 用 POST 会返回 {@code errorCode=5 请求方式错误}。
     *
     * @return 展示用余额字符串，例如 {@code "12.34"}
     */
    public static String queryWallet() throws Exception {
        Session s = Session.get();
        if (s.projectId == null || s.projectId.isEmpty()) {
            throw new ApiException(1, "尚未获取到 projectId，无法查询余额");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectId", s.projectId);
        params.put("telPhone", s.telephone);
        JSONObject data = Http.checkData(Http.get(ApiConfig.EP_WALLET, params));
        String money = data.optString("money", "");
        if (!money.isEmpty()) {
            s.accountMoney = money;
            s.save();
        }
        return money.isEmpty() ? s.accountMoney : money;
    }

    /** 手动设置 projectId（小程序里绑定学校后仍取不到时的兜底手段）。 */
    public static void setProjectId(String projectId) {
        Session s = Session.get();
        s.projectId = projectId == null ? "" : projectId.trim();
        s.save();
    }

    public static void refreshAccount() throws Exception {
        Session s = Session.get();
        String pid = s.projectId;
        if (pid == null || pid.isEmpty()) {
            throw new ApiException(1,
                    "尚未获取到 projectId，无法刷新。请先在「趣智校园」小程序里绑定学校，"
                            + "或长按账号卡片手动填写项目 ID");
        }
        // 注意：/user/info 强制要求 projectId，传空会返回
        // errorCode=1「参数校验不通过: projectId不能为空」
        bindProject(pid);
    }

    /**
     * 校验当前登录态是否仍然有效。
     *
     * <p>小程序对 {@code errorCode 8}（未获取到用户信息，请重新登录）与
     * {@code errorCode 10000} 的处理都是"清空本地存储并跳回登录页"
     * （app-service.js:53492、53499），这里保持一致。
     *
     * <p>本应用在每次进入页面时都会调用它，避免拿一个过期的 loginCode
     * 去走完整条下单流程才发现失败。
     *
     * @throws ApiException {@link ApiException#isSessionExpired()} 为真时需要重新登录
     */
    public static void assertSessionValid() throws Exception {
        Session s = Session.get();
        if (!s.isLoggedIn()) {
            throw new ApiException(8, "登录已失效，请重新登录");
        }
        if (s.projectId == null || s.projectId.isEmpty()) {
            throw new ApiException(2, "账号尚未绑定学校，请先在小程序里完成绑定");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectId", s.projectId);
        JSONObject response = Http.get(ApiConfig.EP_USER_INFO, params);
        int code = response.optInt("errorCode", 0);
        if (code != 0) {
            throw new ApiException(code,
                    response.optString("errorMessage",
                            response.optString("message", "登录状态校验失败")));
        }
        JSONObject data = response.optJSONObject("data");
        if (data != null) {
            s.loginCode = data.optString("loginCode", s.loginCode);
            s.telephone = data.optString("telephone", s.telephone);
            applyAccount(data);
            s.save();
        }
    }

    /**
     * 原样返回 {@code /user/info} 的响应，仅供排错。
     *
     * <p>当 {@code projectId} 取不到时，把这段打出来就能看到服务端到底给了哪些字段。
     */
    public static String fetchUserInfoRaw() throws Exception {
        return Http.get(ApiConfig.EP_USER_INFO, new LinkedHashMap<>()).toString();
    }

    // ------------------------------------------------------------------ 设备

    /**
     * 按 MAC 查设备档案（app-service.js:74225）。
     *
     * <p>小程序在扫描页拿到 MAC 后立刻调这个接口换回设备档案——
     * 客户端不保存任何设备业务信息，只提供 MAC。
     */
    public static List<DeviceRecord> findDevices(String mac) throws Exception {
        Session s = Session.get();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("projectId", s.projectId);
        params.put("macAddressList", mac);
        params.put("isNew", "1");   // app-service.js:53416 对 /device/list/mac 固定加这个
        JSONObject response = Http.get(ApiConfig.EP_DEVICE_LIST_MAC, params);
        int code = response.optInt("errorCode", 0);
        if (code != 0) {
            throw new ApiException(code,
                    response.optString("errorMessage", response.optString("message", "查询设备失败")));
        }
        List<DeviceRecord> out = new ArrayList<>();
        JSONArray arr = response.optJSONArray("data");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                DeviceRecord d = DeviceRecord.fromJson(o);
                d.bleAddress = mac;
                out.add(d);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 用水

    /**
     * 下单：请求服务端生成要下发给设备的报文。
     *
     * <p>对应 app-service.js:71470 的 {@code downRate_water}。
     * <b>报文由服务端签发，客户端只是搬运工</b>——所以改客户端无法凭空出水，
     * 计费与准入判断也全在服务端。
     *
     * @param macAddress6 设备 {@code 0x23} 响应里偏移 26 起的 6 字节随机数。
     *                    注意：这个字段名叫 macAddress，但<b>不是真实 MAC</b>，属历史命名遗留。
     * @param randomNumber 设备 {@code 0x23} 响应里解析出的 tactimes
     * @param protocolType 设备 {@code 0x23} 响应里解析出的 CommMode
     * @return 下单结果，含 {@code downData}（CMD 0x21 的 DATA）与闲置自动关停时长
     */
    public static RateOrder downRate(DeviceRecord dev, String macAddress6,
                                     String randomNumber, String protocolType) throws Exception {
        Session s = Session.get();
        if (!s.isLoggedIn()) {
            throw new IllegalStateException("未登录");
        }
        // 与小程序保持一致：consumeMothe / xfMothe 都硬编码为 0（app-service.js:71188-71189）
        String consumeMothe = "0";
        String xfModel = "0";

        String raw = "deviceId" + dev.devId
                + "randomNumber" + randomNumber
                + "telephone" + s.telephone
                + "xfModel" + xfModel
                + "&key=" + s.loginCode;
        String signature = Md5Util.md5(
                Md5Util.md5(raw) + "&key=" + ApiConfig.SIGN_KEY);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("deviceId", dev.devId);
        params.put("macAddress", macAddress6);
        params.put("bigTypeId", String.valueOf(dev.bigTypeId));
        params.put("smallTypeId", String.valueOf(dev.smallTypeId));
        params.put("consumeMothe", consumeMothe);
        params.put("randomNumber", randomNumber);
        params.put("signature", signature);
        params.put("xfModel", xfModel);
        params.put("protocolType", protocolType);
        params.put("telephone", s.telephone);
        params.put("projectId", dev.projectId.isEmpty() ? s.projectId : dev.projectId);

        JSONObject data = Http.checkData(Http.post(ApiConfig.EP_RATE_ORDER, params));
        RateOrder order = RateOrder.fromJson(data);
        if (order.downData.isEmpty()) {
            throw new ApiException(-1, "服务端未返回 downData");
        }
        return order;
    }

    /**
     * 上传消费记录并换取"清零指令"。
     *
     * <p>对应 app-service.js:42237 的 {@code UpLoadHttpV3}。
     * 服务端结算后返回 AES 密文 {@code clData}，客户端解密出 {@code xxx-yyy}，
     * 取后半段作为 CMD 0x86 的 DATA 去清零设备。
     *
     * <p><b>清零指令同样由服务端签发</b>，客户端无法自行伪造。
     *
     * @param xfData 设备 {@code 0x85} 响应的原始 DATA（十六进制串）
     * @return 服务端返回体；调用方从中取 {@code clData}
     */
    public static JSONObject uploadConsume(String xfData, String randomNumber,
                                           String protocolType) throws Exception {
        Session s = Session.get();
        String raw = "loginCode" + s.loginCode
                + "telephone" + s.telephone
                + "xfData" + xfData
                + "&key=" + s.loginCode;
        String signature = Md5Util.md5(
                Md5Util.md5(raw) + "&key=" + ApiConfig.SIGN_KEY);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("protocolType", protocolType);
        params.put("telephone", s.telephone);
        params.put("loginCode", s.loginCode);
        params.put("xfData", xfData);
        params.put("signature", signature);
        params.put("randomNumber", randomNumber);
        params.put("projectId", s.projectId);
        return Http.post(ApiConfig.EP_UPLOAD_DATA, params);
    }

    /**
     * 设备对 CMD 0x21 应答失败时，通知服务端撤销这一单。
     *
     * <p>对应 app-service.js:42181。说明服务端在下单时就已经乐观建单并预扣款，
     * 靠这次回调做补偿。
     */
    public static void reportFail(String consumeDate) throws Exception {
        Session s = Session.get();
        String raw = "consumeDate" + consumeDate
                + "loginCode" + s.loginCode
                + "telephone" + s.telephone
                + "&key=" + s.loginCode;
        String signature = Md5Util.md5(
                Md5Util.md5(raw) + "&key=" + ApiConfig.SIGN_KEY);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("consumeDate", consumeDate);
        params.put("telephone", s.telephone);
        params.put("signature", signature);
        params.put("projectId", s.projectId);
        Http.post(ApiConfig.EP_UPLOAD_FAIL, params);
    }

    // ------------------------------------------------------------------ 结算

    /**
     * 直查某单结算了多少（{@code POST /order/consumeOrder/result/query}）。
     *
     * <p>这条路要 {@code snCode} 与 {@code orderNo} 同时具备，联网设备才有；
     * 纯蓝牙设备一般拿不到 snCode，请用 {@link #queryConsumeMoneyFromBill}。
     *
     * @return 元；服务端还没填金额时返回 {@code null}（<b>不是 0</b>）
     */
    public static Double queryConsumeMoney(String orderNo, String snCode) throws Exception {
        if (orderNo == null || orderNo.isEmpty() || snCode == null || snCode.isEmpty()) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("snCode", snCode);
        params.put("orderNo", orderNo);
        params.put("projectId", Session.get().projectId);
        JSONObject response = Http.post(ApiConfig.EP_CONSUME_RESULT_QUERY, params);
        if (response.optInt("errorCode", 0) != 0) {
            return null;
        }
        JSONObject data = response.optJSONObject("data");
        if (data == null) {
            return null;
        }
        Double money = Money.parse(data.opt("consumeMoney"));
        // 结算没完成时这个字段就是数字 0，和"真的没花钱"分不开；
        // 交给调用方结合账单列表判断，这里只如实返回。
        return money;
    }

    /**
     * 查某个月的账单列表。
     *
     * @param month {@code yyyy-MM}；服务端只按月返回，没有分页
     */
    public static List<BillRecord> queryBills(String month) throws Exception {
        // 光靠公共参数不够：这个接口要 telephone 才认得出账户，
        // 缺了会返回 errorCode=8「未获取到账户信息，请重新登录」（真机实测）
        Map<String, String> params = accountParams();
        params.put("month", month);
        params.put("billRequestType", "2");
        JSONObject response = Http.get(ApiConfig.EP_BILL_LIST, params);
        int code = response.optInt("errorCode", 0);
        if (code != 0) {
            throw new ApiException(code,
                    response.optString("errorMessage",
                            response.optString("message", "查询账单失败")));
        }
        return BillRecord.parse(response.optJSONArray("data"));
    }

    /**
     * 从当月账单里把这一单捞出来（蓝牙表结算的兜底路径）。
     *
     * <p>为什么要放宽时间窗：账单的 {@code consumeDate} 打的是<b>下单那一刻</b>，
     * 而客户端记的开始时刻是<b>开阀确认成功之后</b>，天然晚 1~2 秒；
     * 用 {@code >=} 卡会把这一单自己滤掉（参考项目踩过这个坑）。
     *
     * @param orderNo       有就精确匹配，没有就靠时间窗
     * @param windowStartMs 开始用水的时刻（毫秒）
     * @return 元；没匹配到或金额还是占位值时返回 {@code null}
     */
    public static Double queryConsumeMoneyFromBill(String orderNo, long windowStartMs)
            throws Exception {
        String month = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("month", month);
        params.put("billRequestType", "2");
        JSONObject response = Http.get(ApiConfig.EP_BILL_LIST, params);
        if (response.optInt("errorCode", 0) != 0) {
            return null;
        }
        JSONArray arr = response.optJSONArray("data");
        if (arr == null) {
            return null;
        }
        long window = windowStartMs - BILL_TIME_SLACK_MS;
        JSONObject best = null;
        long bestTime = -1;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONObject bill = item.optJSONObject("consumeBillDTO");
            if (bill == null) {
                bill = item;
            }
            String no = bill.optString("orderNo", bill.optString("orderId", ""));
            if (orderNo != null && !orderNo.isEmpty()
                    && (orderNo.equals(bill.optString("orderNo"))
                    || orderNo.equals(bill.optString("orderId")))) {
                best = bill;
                break;
            }
            long t = parseBillTime(bill.optString("consumeDate", ""));
            if (t >= window && t > bestTime) {
                bestTime = t;
                best = bill;
            }
        }
        if (best == null) {
            return null;
        }
        // 账单里的 consumeMoney 是字符串、单位已经是元
        Double money = Money.parse(best.opt("consumeMoney"));
        return money != null && money > 0 ? money : null;
    }

    /** 账单时间窗向前放宽量：下单与开阀确认之间的那点延迟。 */
    private static final long BILL_TIME_SLACK_MS = 5 * 60 * 1000L;

    /** 把 {@code 20261007031245} / {@code 2026-10-07 03:12:45} 这种串解析成毫秒。 */
    public static long parseBillTime(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < 14) {
            return -1;
        }
        try {
            Date d = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse(
                    digits.substring(0, 14));
            return d == null ? -1 : d.getTime();
        } catch (Exception e) {
            return -1;
        }
    }
}
