package com.minipay.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 网关启动类
 *
 * 面试考点：
 * 1. Spring Cloud Gateway 基于 WebFlux（Netty），非阻塞异步模型
 * 2. 核心概念：Route(路由) + Predicate(断言) + Filter(过滤器)
 * 3. 网关职责：路由转发、鉴权、限流、跨域、日志
 */
@SpringBootApplication
@EnableDiscoveryClient
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
