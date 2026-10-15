package dev.answer.campuswater.api;

import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 消费记录清零数据的解密。
 *
 * <p>对应 app-service.js:71444 的 {@code Decrypt}：CryptoJS 用
 * AES / ECB / Pkcs7，密钥 {@code 20210118klcx@002}（即 {@link ApiConfig#AES_KEY}）。
 * ECB 模式不使用 IV，源码里传的 iv 实际无效。
 *
 * <p>Java 的 "AES/ECB/PKCS5Padding" 与 PKCS7 在 AES 分组长度下等价。
 */
public final class AesUtil {

    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    private AesUtil() {
    }

    /**
     * 解密服务端返回的 clData。
     *
     * @param cipherBase64 CryptoJS 输出的 Base64 密文
     * @return 明文，形如 {@code xxxx-yyy}；失败返回 null
     */
    public static String decrypt(String cipherBase64) {
        if (cipherBase64 == null || cipherBase64.isEmpty()) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            SecretKeySpec key = new SecretKeySpec(
                    ApiConfig.AES_KEY.getBytes(StandardCharsets.UTF_8), "AES");
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] plain = cipher.doFinal(android.util.Base64.decode(
                    cipherBase64, android.util.Base64.DEFAULT));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从解密明文里取出要下发给设备的清零数据。
     *
     * <p>明文形如 {@code xxxx-yyy}，设备侧只认 {@code yyy}
     * （app-service.js:71444 之后调用处的 split("-")[1]）。
     */
    public static String extractClearData(String plain) {
        if (plain == null) {
            return null;
        }
        int idx = plain.indexOf('-');
        if (idx >= 0 && idx + 1 < plain.length()) {
            return plain.substring(idx + 1);
        }
        return plain;
    }
}
