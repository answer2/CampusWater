package dev.answer.campuswater.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** MD5 工具。协议里所有摘要都取<b>小写十六进制</b>。 */
public final class Md5Util {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Md5Util() {
    }

    /** 返回 32 位小写十六进制 MD5。 */
    public static String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int v = bytes[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }
}
