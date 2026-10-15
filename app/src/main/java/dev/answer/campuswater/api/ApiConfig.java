package dev.answer.campuswater.api;

/**
 * 接口与协议常量。
 *
 * <p>取值来源：小程序 wx2ded7c2a06ef15ea (v7.3.7) 的 app-service.js。
 * 主域名为 v3-api.china-qzxy.cn（globalData.urlV3，app-service.js:53201），
 * 旧域名为 wfwapi.china-qzxy.cn（globalData.url，app-service.js:53221）。
 *
 * <p><b>注意</b>：签名密钥与 AES 密钥是设备运营方在客户端中硬编码的凭据。
 * 在自有客户端中复用它们，等同于使用他人凭据访问其系统，请先取得运营方授权。
 */
public final class ApiConfig {

    private ApiConfig() {
    }

    /** V3 接口主域名。 */
    public static final String BASE_V3 = "https://v3-api.china-qzxy.cn";

    /** 旧版接口域名。 */
    public static final String BASE_OLD = "https://wfwapi.china-qzxy.cn";

    /** 客户端版本号，服务端可能在部分接口做版本校验（app-service.js:53218）。 */
    public static final String VERSION = "7.3.7";

    /** 微信小程序的平台标记（app-service.js:53475）。 */
    public static final String PHONE_SYSTEM_WECHAT = "WeChat";

    /**
     * 客户端身份候选值。
     *
     * <p>服务端靠这个字段判断"当前项目是否支持该应用"。昭通学院这类项目只允许
     * 支付宝小程序接入，用微信身份会被拒：
     * 「当前项目不支持该应用，请使用"趣智校园"支付宝小程序」。
     *
     * <p>支付宝端的内部平台枚举是 {@code "alipay"}（见支付宝分包 index.worker.js:35110
     * 的 {@code e.alipay = "alipay"}），但它发给服务端的 {@code phoneSystem} 具体字符串
     * 无法从现有反编译产物中确定，因此这里做成可切换项，默认先按支付宝身份发。
     */
    public static final String[] CLIENT_PLATFORMS = {"AliPay", "alipay", "支付宝", "WeChat"};

    /** 默认客户端身份。 */
    public static final String DEFAULT_CLIENT_PLATFORM = "AliPay";

    /** 服务端的客户端类型编号：微信 = 2，支付宝 = 3。 */
    public static int clientTypeFor(String platform) {
        return "WeChat".equalsIgnoreCase(platform) ? 2 : 3;
    }

    /**
     * 给界面看的中文身份名。
     *
     * <p>{@code phoneSystem} 的取值是服务端协议字段（AliPay / alipay / WeChat），
     * 但界面上不该让用户去读这些英文 —— 只在"切换身份"那一层把原值作为副标题保留，
     * 方便排查时对照抓包。
     */
    public static String platformLabel(String platform) {
        if (platform == null) {
            return "支付宝端";
        }
        if ("WeChat".equalsIgnoreCase(platform)) {
            return "微信端";
        }
        if (platform.contains("支付宝")) {
            return "支付宝端";
        }
        return "支付宝端";
    }

    // ---------------------------------------------------------------- 登录

    /** GET 发送短信验证码（package_operationalGuidelines/app-service.js:9941）。 */
    public static final String EP_VERIFY_CODE = "/user/verification/code/get";

    /** POST 短信验证码注册并登录，type=5（package_operationalGuidelines/app-service.js:10000）。 */
    public static final String EP_REGISTER_AND_LOGIN = "/user/registerAndLogin";

    /** POST 账号密码登录（app-service.js:42452）。 */
    public static final String EP_LOGIN = "/user/login";

    /** GET 用户信息，用于拿到 accountId 并切换项目（app-service.js:52639）。 */
    public static final String EP_USER_INFO = "/user/info";

    /**
     * GET 钱包余额（configApiV3.Wallet）。
     *
     * <p>调用样例见 app-service.js:44521 的 {@code getWalletMoneyV3}（只传 projectId）。
     * 该模块里 {@code o = e.httpRequestAgainGet}（44420），所以是 <b>GET</b>；
     * 用 POST 会返回 {@code errorCode=5 请求方式错误}。
     * 返回体 {@code data.money} 为展示用余额。
     */
    public static final String EP_WALLET = "/account/wallet";

    /**
     * GET 模块供水时段（configApiV3.moduleSupplyTime）。
     *
     * <p>调用样例见 app-service.js:43980 的 {@code moduleSupplyTimeV3}：
     * 入参 {@code telephone / moduleId / projectId}，返回
     * {@code data: [{startTime, stopTime, …}]}。
     * 该模块 {@code s = o.httpRequestAgainGet}（43554），所以是 <b>GET</b>。
     */
    public static final String EP_MODULE_SUPPLY_TIME = "/project/module/use/paragraph/list";

    /**
     * bigTypeId → moduleId 的对应关系。
     *
     * <p>来自 app-service.js:69582-69613 的模块图片表：
     * <pre>
     *   1 公寓洗澡  2 澡堂  3 洗衣  4 饮水  5 吹风  6 无线澡堂  7 充电  8 其他
     * </pre>
     */
    public static int moduleIdFor(int bigTypeId) {
        switch (bigTypeId) {
            case BIG_TYPE_BATH:
                return 1;      // 洗澡 → 公寓洗澡
            case BIG_TYPE_WATER:
                return 4;      // 饮水 / 用水
            case BIG_TYPE_BLOWER:
                return 5;      // 吹风
            case BIG_TYPE_CHARGE:
                return 7;      // 充电
            default:
                return 1;
        }
    }

    // ------------------------------------------------- 充值（官方 Android 客户端）

    /**
     * GET 可用的支付方式。
     *
     * <p>来源：官方 Android App（com.klcxkj.zqxy）抓包。
     * 返回 {@code data.payWay: [2, 22]}（2 = 支付宝）。
     */
    public static final String EP_RECHARGE_PAY_MODE = "/settlement/recharge/pay/mode";

    /** GET 充值面额列表，返回 {@code data: [{typeId, amount, givenMoney}]}。 */
    public static final String EP_RECHARGE_PACKAGE = "/settlement/recharge/package/list";

    /**
     * 充值签名里追加的固定后缀。
     *
     * <p>来自支付宝小程序 {@code xaiochen/splitchunk~2/index.worker.js} 的充值页：
     * <pre>
     *   v = "loginCode" + loginCode + "payWay" + payWay
     *     + "rechargeMoney" + rechargeMoney + "telPhone" + telPhone + "sign-kailu=";
     *   signature = md5(v).toLowerCase();
     * </pre>
     *
     * <p>注意这个 {@code "sign-kailu="} 是<b>拼进明文的字面量</b>（等号后面没有值），
     * 和蓝牙下单用的 {@link #SIGN_KEY}（{@code sign-kailu-855c74a8…}）不是一回事。
     * 已用官方 App 的成功抓包验证过签名结果（抓包里的手机号这里就不抄了）。
     */
    public static final String RECHARGE_SIGN_SUFFIX = "sign-kailu=";

    /** 充值支付方式：2 = 支付宝（支付宝小程序常量 RECHARGE_WAY）。 */
    public static final int RECHARGE_WAY_ALIPAY = 2;

    /**
     * 小程序 AppID，充值请求里作为 {@code appId} 上传。
     *
     * <p>支付宝小程序充值页用的是 {@code globalData.appId}
     * （app-service.js:53224 的 {@code appId: "wx2ded7c2a06ef15ea"}）。
     */
    public static final String WECHAT_APP_ID = "wx2ded7c2a06ef15ea";

    /**
     * POST 发起充值，返回支付宝 App 支付串。
     *
     * <p>返回的 {@code data} 是 {@code app_id=…&biz_content=…&sign=…} 形式的
     * 支付宝 {@code alipay.trade.app.pay} 订单串，可直接交给支付宝 SDK 拉起收银台。
     * 回调地址 {@code notify_url=/settlement/alipay/notify/balance} 指向服务端，
     * 所以入账由服务端完成，客户端不需要（也拿不到）支付结果。
     */
    public static final String EP_RECHARGE_BALANCE = "/settlement/alipay/recharge/balance";

    // ---------------------------------------------------------------- 设备

    /** GET 按 MAC 查设备档案（configApiV3.deviceInfo，app-service.js:45264）。 */
    public static final String EP_DEVICE_LIST_MAC = "/device/list/mac";

    // ---------------------------------------------------------------- 用水

    /** POST 蓝牙设备下单，返回 downData（app-service.js:45327 区域 downRateApartmentWater）。 */
    public static final String EP_RATE_ORDER = "/order/wechatApplet/downRate/bluetooth/rateOrder";

    /** POST 上报考勤/消费记录（app-service.js:42237）。 */
    public static final String EP_UPLOAD_DATA = "/order/upload/bluetooth/data";

    /** POST 下单失败回滚（app-service.js:42181 附近）。 */
    public static final String EP_UPLOAD_FAIL = "/order/upload/bluetooth/fail";

    /**
     * GET 账单列表（按月份聚合）。
     *
     * <p>参数 {@code month=yyyy-MM}、{@code billRequestType=2}。
     * 本应用用它做结算兜底：{@code upload/bluetooth/data} 偶发先回占位金额，
     * 过一两秒账单里才是真金额。
     * <b>注意这里的 {@code consumeMoney} 是字符串、单位是元</b>，
     * 和 {@link #EP_CONSUME_RESULT_QUERY} 返回的数字型（厘）不是一回事，见 {@link Money}。
     */
    public static final String EP_BILL_LIST = "/order/query/account/bill/list";

    /**
     * POST 直查某单结算金额。
     *
     * <p>{@code snCode + orderNo} 都有才查得到——联网设备走这条，
     * 纯蓝牙设备通常拿不到 snCode，只能靠账单列表兜底。
     */
    public static final String EP_CONSUME_RESULT_QUERY = "/order/consumeOrder/result/query";

    // ---------------------------------------------------------------- 密钥

    /** 下单签名密钥（app-service.js:71479）。 */
    public static final String SIGN_KEY = "sign-kailu-855c74a88b8b494187c99b08b8c9a744";

    /** 消费记录清零数据解密密钥（app-service.js:71445）。 */
    public static final String AES_KEY = "20210118klcx@002";

    // ---------------------------------------------------------------- 设备类型

    public static final int BIG_TYPE_BATH = 4;      // 洗澡
    public static final int BIG_TYPE_WATER = 5;     // 饮水 / 用水
    public static final int BIG_TYPE_BLOWER = 7;    // 吹风
    public static final int BIG_TYPE_CHARGE = 8;    // 充电
    public static final int BIG_TYPE_AIRCON = 9;    // 空调

    public static String bigTypeName(int bigTypeId) {
        switch (bigTypeId) {
            case BIG_TYPE_BATH:
                return "洗澡";
            case BIG_TYPE_WATER:
                return "饮水";
            case BIG_TYPE_BLOWER:
                return "吹风机";
            case BIG_TYPE_CHARGE:
                return "充电";
            case BIG_TYPE_AIRCON:
                return "空调";
            default:
                return "设备";
        }
    }
}
