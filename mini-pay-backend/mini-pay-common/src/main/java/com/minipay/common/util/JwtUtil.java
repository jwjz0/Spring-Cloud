package com.minipay.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类
 *
 * 面试考点：
 * 1. JWT 由三部分组成：Header.Payload.Signature
 * 2. Token 无状态，服务端不存储，适合微服务架构
 * 3. 签名使用 HMAC-SHA256，防止篡改
 */
public class JwtUtil {

    private static final String SECRET = "MiniPaySecretKey2026MiniPaySecretKey2026"; // 至少32字节
    private static final long EXPIRE_MS = 24 * 60 * 60 * 1000; // 24小时

    private static SecretKey getKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT Token
     */
    public static String generateToken(Long userId, String username) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRE_MS))
                .signWith(getKey())
                .compact();
    }

    /**
     * 解析 Token，返回 Claims
     */
    public static Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 Token 中获取用户ID
     */
    public static Long getUserId(String token) {
        return Long.parseLong(parseToken(token).getSubject());
    }

    /**
     * 从 Token 中获取用户名
     */
    public static String getUsername(String token) {
        return parseToken(token).get("username", String.class);
    }
}
