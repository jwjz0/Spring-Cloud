package com.minipay.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * 全局鉴权过滤器
 *
 * 面试考点：
 * 1. GlobalFilter 对所有路由生效，GatewayFilter 对指定路由生效
 * 2. 过滤器链模式（责任链模式）
 * 3. Gateway 解析 Token 后，将 userId 放入请求头传递给下游服务
 *    这样下游服务不需要重复解析 Token
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthGlobalFilter.class);

    private static final String SECRET = "MiniPaySecretKey2026MiniPaySecretKey2026";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    // 白名单 - 不需要登录的接口
    private static final List<String> WHITE_LIST = List.of(
            "/api/user/login",
            "/api/user/register",
            "/api/pay/callback",
            "/api/pay/refund/callback"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = readOrCreateRequestId(exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER));
        ServerHttpRequest requestWithRequestId = exchange.getRequest().mutate()
                .headers(headers -> headers.set(REQUEST_ID_HEADER, requestId))
                .build();
        ServerWebExchange exchangeWithRequestId = exchange.mutate().request(requestWithRequestId).build();
        exchangeWithRequestId.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);

        String path = exchangeWithRequestId.getRequest().getURI().getPath();

        // 白名单直接放行
        if (isWhitelisted(path)) {
            return chain.filter(exchangeWithRequestId);
        }

        // 从请求头获取 Token
        String authHeader = exchangeWithRequestId.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Unauthorized request(no token), requestId={}, path={}", requestId, path);
            exchangeWithRequestId.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchangeWithRequestId.getResponse().setComplete();
        }

        String token = authHeader.substring(7);
        try {
            // 解析 JWT
            SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = claims.getSubject();
            String username = claims.get("username", String.class);

            // 将用户信息放入请求头，传递给下游微服务
            ServerHttpRequest request = exchangeWithRequestId.getRequest().mutate()
                    .headers(headers -> {
                        headers.set("X-User-Id", userId);
                        headers.set("X-Username", username);
                        headers.set(REQUEST_ID_HEADER, requestId);
                    })
                    .build();

            return chain.filter(exchangeWithRequestId.mutate().request(request).build());
        } catch (Exception e) {
            log.warn("Unauthorized request(token invalid), requestId={}, path={}, reason={}", requestId, path, e.getMessage());
            exchangeWithRequestId.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchangeWithRequestId.getResponse().setComplete();
        }
    }

    private boolean isWhitelisted(String path) {
        return WHITE_LIST.stream().anyMatch(whitePath -> path.equals(whitePath) || path.startsWith(whitePath + "/"));
    }

    private String readOrCreateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return requestId;
    }

    @Override
    public int getOrder() {
        return -1; // 优先级最高
    }
}
