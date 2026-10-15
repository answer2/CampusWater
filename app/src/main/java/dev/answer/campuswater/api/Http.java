package dev.answer.campuswater.api;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简 HTTP 客户端，刻意不引入第三方库。
 *
 * <p>复刻 app-service.js:53461 的 {@code httpRequestAgain} 与
 * app-service.js:53404 的 {@code httpRequestAgainGet} 的行为：
 * <ul>
 *   <li>POST 用 {@code application/x-www-form-urlencoded}，参数在请求体</li>
 *   <li>GET 把参数拼到 query</li>
 *   <li>每个请求自动附加 phoneSystem / version，已登录时附加 loginCode / userId / accountId</li>
 *   <li>业务错误通过 HTTP 200 + errorCode 返回，不是 HTTP 错误码</li>
 * </ul>
 *
 * <p><b>必须在后台线程调用。</b>
 */
public final class Http {

    private static final int TIMEOUT_MS = 15000;

    private Http() {
    }

    /** 发送 POST（form-urlencoded），自动附加公共参数。 */
    public static JSONObject post(String path, Map<String, String> params)
            throws IOException, JSONException {
        LinkedHashMap<String, String> body = withCommon(params);
        byte[] payload = encodeForm(body).getBytes(StandardCharsets.UTF_8);
        return request("POST", path, null, payload);
    }

    /** 发送 GET，自动附加公共参数（拼在 query 上）。 */
    public static JSONObject get(String path, Map<String, String> params)
            throws IOException, JSONException {
        LinkedHashMap<String, String> query = withCommon(params);
        return request("GET", path, encodeForm(query), null);
    }

    /**
     * 取出 data 字段；errorCode != 0 时抛 {@link ApiException}。
     *
     * <p>对应小程序里每个回调开头的 {@code 0 == e.data.errorCode} 判断。
     */
    public static JSONObject checkData(JSONObject response) throws ApiException {
        int code = response.optInt("errorCode", 0);
        if (code != 0) {
            String msg = response.optString("errorMessage",
                    response.optString("message", "未知错误"));
            throw new ApiException(code, msg);
        }
        JSONObject data = response.optJSONObject("data");
        return data == null ? new JSONObject() : data;
    }

    // ------------------------------------------------------------------ 内部

    private static LinkedHashMap<String, String> withCommon(Map<String, String> params) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (params != null) {
            out.putAll(params);
        }
        Session session = Session.get();
        String platform = session == null ? ApiConfig.DEFAULT_CLIENT_PLATFORM : session.platform;
        putIfPresent(out, "phoneSystem", platform);
        putIfPresent(out, "version", ApiConfig.VERSION);
        putIfPresent(out, "clientType", String.valueOf(ApiConfig.clientTypeFor(platform)));

        if (session != null && session.isLoggedIn()) {
            putIfPresent(out, "loginCode", session.loginCode);
            putIfPresent(out, "userId", session.userId);
            putIfPresent(out, "accountId", session.accountId);
        }
        return out;
    }

    private static void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }

    private static String encodeForm(Map<String, String> params) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    private static JSONObject request(String method, String path, String query, byte[] body)
            throws IOException, JSONException {
        String url = ApiConfig.BASE_V3 + path + (query != null && !query.isEmpty() ? "?" + query : "");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }
            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(is);
            if (status != 200) {
                throw new IOException("HTTP " + status + " : " + text);
            }
            return new JSONObject(text);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        try (InputStream in = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        }
    }
}
