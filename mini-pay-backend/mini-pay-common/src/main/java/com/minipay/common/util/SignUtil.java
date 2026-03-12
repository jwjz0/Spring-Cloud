package com.minipay.common.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 签名工具（HmacSHA256）
 */
public final class SignUtil {

    private static final String HMAC_SHA_256 = "HmacSHA256";

    private SignUtil() {
    }

    /**
     * 按 key 升序将参数拼接为 key=value&key2=value2 后签名。
     * sign 字段本身不会参与签名。
     */
    public static String signMap(Map<String, ?> params, String secret) {
        if (params == null || params.isEmpty()) {
            return signText("", secret);
        }
        Map<String, String> canonicalMap = new TreeMap<>();
        for (Map.Entry<String, ?> entry : params.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isBlank() || "sign".equalsIgnoreCase(key) || value == null) {
                continue;
            }
            canonicalMap.put(key, String.valueOf(value));
        }
        String canonical = canonicalMap.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
        return signText(canonical, secret);
    }

    public static boolean verifyMap(Map<String, ?> params, String secret, String signature) {
        if (signature == null) {
            return false;
        }
        return secureEquals(signMap(params, secret), signature);
    }

    public static String signText(String text, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret cannot be blank");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            byte[] digest = mac.doFinal((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return toHexLowerCase(digest);
        } catch (Exception e) {
            throw new IllegalStateException("sign failed", e);
        }
    }

    public static boolean verifyText(String text, String secret, String signature) {
        if (signature == null) {
            return false;
        }
        return secureEquals(signText(text, secret), signature);
    }

    private static boolean secureEquals(String a, String b) {
        return MessageDigest.isEqual(
                a == null ? new byte[0] : a.getBytes(StandardCharsets.UTF_8),
                b == null ? new byte[0] : b.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String toHexLowerCase(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int value = b & 0xff;
            if (value < 0x10) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(value));
        }
        return sb.toString();
    }
}

