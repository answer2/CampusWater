package dev.answer.campuswater.api;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 拉起支付宝完成 App 支付。
 *
 * <p>用<b>反射</b>调用支付宝 SDK，这样即使工程里没有加
 * {@code com.alipay.sdk:alipaysdk-android} 依赖也能正常编译与运行——
 * 只是会返回"未安装/未集成"。
 *
 * <p>依赖已通过 {@code gradle/libs.versions.toml} 的 {@code alipay-sdk} 引入，
 * 由 {@code app/build.gradle.kts} 应用。之所以仍用反射，是为了让这个依赖保持"可选"：
 * 万一某个分发渠道不便携带 SDK，去掉依赖只会让充值提示"未集成"，
 * 而不会导致整个工程编译失败。
 *
 * <p>关键点：支付宝收银台要求在<b>主线程</b>调用，且需要一个
 * {@code android.os.Handler} 接收结果回调。
 */
public final class AliPayLauncher {

    private static final String TAG = "AliPayLauncher";

    private static final String PAY_TASK_CLASS = "com.alipay.sdk.app.PayTask";
    private static final String ALIPAY_CLIENT_PACKAGE = "com.eg.android.AlipayGphone";

    /** Handler 消息标记：{@code msg.obj} 里直接是 {@link Result}，而不是 SDK 的原始 Map。 */
    private static final int WHAT_ERROR = -100;

    private AliPayLauncher() {
    }

    /** 支付结果。 */
    public static final class Result {
        public final int resultCode;
        public final String memo;

        Result(int resultCode, String memo) {
            this.resultCode = resultCode;
            this.memo = memo == null ? "" : memo;
        }

        /** 9000 = 支付成功。 */
        public boolean isSuccess() {
            return resultCode == 9000;
        }

        /** 6001 = 用户中途取消。 */
        public boolean isCancelled() {
            return resultCode == 6001;
        }

        /** 8000 / 6004 = 支付结果待确认（通常需要查询订单）。 */
        public boolean isPending() {
            return resultCode == 8000 || resultCode == 6004;
        }

        /** 4000 / 5000 / 6002 = 失败。 */
        public boolean isFailure() {
            return resultCode == 4000 || resultCode == 5000 || resultCode == 6002;
        }

        /** 给用户看的中文描述。 */
        public String describe() {
            switch (resultCode) {
                case 9000:
                    return "支付成功";
                case 8000:
                    return "正在处理中，请稍后刷新余额确认";
                case 4000:
                    return "订单支付失败（订单参数错误）";
                case 5000:
                    return "重复请求";
                case 6001:
                    return "用户中途取消";
                case 6002:
                    return "网络连接出错";
                case 6004:
                    return "支付结果未知，请稍后刷新余额确认";
                case -1:
                    return "订单串为空";
                case -2:
                    return "未集成支付宝 SDK";
                case -3:
                    return "拉起支付宝失败";
                default:
                    return "未知状态（" + resultCode + "）";
            }
        }

        @Override
        public String toString() {
            return "code=" + resultCode + " " + memo;
        }
    }

    public interface Callback {
        void onResult(Result result);
    }

    /** 是否安装了支付宝客户端。 */
    public static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(ALIPAY_CLIENT_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * 拉起支付宝收银台。可在任意线程调用，结果通过 {@code callback} 回到主线程。
     *
     * <p><b>两个必须守住的点</b>（都是踩过 ANR 之后才确认的）：
     *
     * <ol>
     *   <li><b>{@code payV2} 的第二个参数必须传 {@code false}</b>。
     *       它是 {@code isShowPayLoading}；传 {@code true} 时 SDK 内部会在
     *       {@code synchronized (IAlixPay.class)} 上 {@code wait()}，
     *       而唤醒它的机制与"外部 Handler 收结果"的用法不兼容，
     *       实测主线程永久 {@code TimedWaiting}，然后 ANR：
     *       <pre>
     *         "main" TimedWaiting
     *           at java.lang.Object.wait(Native method)
     *           - waiting on (a java.lang.Class&lt;com.alipay.android.app.IAlixPay&gt;)
     *           at com.alipay.sdk.m.u.h.a(SourceFile:226)
     *           at com.alipay.sdk.app.PayTask.payV2(SourceFile:6)
     *           at dev.answer.campuswater.api.AliPayLauncher.pay(AliPayLauncher.java:138)
     *       </pre></li>
     *   <li><b>必须放到子线程调用</b>。{@code payV2} 会同步阻塞到用户在支付宝里操作完
     *       （拉起客户端、等待返回），在主线程调用必然卡住界面。</li>
     * </ol>
     *
     * @param orderStr 服务端返回的完整订单串（含 app_id）
     */
    public static void pay(final Activity activity, final String orderStr, final Callback callback) {
        if (orderStr == null || orderStr.isEmpty()) {
            if (callback != null) {
                callback.onResult(new Result(-1, "订单串为空"));
            }
            return;
        }
        final Handler handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (callback == null) {
                    return;
                }
                if (msg.what == WHAT_ERROR) {
                    callback.onResult((Result) msg.obj);
                } else {
                    callback.onResult(parseResult(msg.obj));
                }
            }
        };

        // 子线程执行，避免 payV2 的同步阻塞拖死主线程
        new Thread(() -> {
            try {
                Class<?> payTaskClass = Class.forName(PAY_TASK_CLASS);
                Object payTask = payTaskClass
                        .getConstructor(Activity.class)
                        .newInstance(activity);
                Method payV2 = payTaskClass.getMethod("payV2", String.class, boolean.class);
                // 第二个参数 = isShowPayLoading，必须 false，否则 SDK 内部死等
                Object raw = payV2.invoke(payTask, orderStr, false);

                Message message = Message.obtain(handler);
                message.obj = raw;
                message.sendToTarget();
            } catch (ClassNotFoundException e) {
                Log.w(TAG, "未集成支付宝 SDK", e);
                postResult(handler, new Result(-2,
                        "未集成支付宝 SDK，请在 build.gradle 添加 alipaysdk-android 依赖"));
            } catch (Throwable t) {
                Log.w(TAG, "拉起支付宝失败", t);
                postResult(handler, new Result(-3, "拉起支付宝失败：" + t.getMessage()));
            }
        }, "alipay-pay").start();
    }

    /** 把错误结果也送回主线程，保证回调线程一致。 */
    private static void postResult(Handler handler, Result result) {
        Message message = Message.obtain(handler);
        message.what = WHAT_ERROR;
        message.obj = result;
        message.sendToTarget();
    }

    /**
     * 解析 payV2 的返回。
     *
     * <p>SDK 返回 {@code Map<String,String>}，里面是
     * {@code resultStatus} / {@code memo} / {@code result}。
     */
    @SuppressWarnings("unchecked")
    private static Result parseResult(Object raw) {
        if (!(raw instanceof Map)) {
            return new Result(-1, "返回格式异常：" + raw);
        }
        Map<String, String> map = (Map<String, String>) raw;
        String status = map.get("resultStatus");
        String memo = map.get("memo");
        int code;
        try {
            code = status == null ? -1 : Integer.parseInt(status);
        } catch (NumberFormatException e) {
            code = -1;
        }
        return new Result(code, memo);
    }
}
