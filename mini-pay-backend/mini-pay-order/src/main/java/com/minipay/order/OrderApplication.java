package com.minipay.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 订单服务启动类
 *
 * 面试考点：
 * @EnableFeignClients 开启 Feign 声明式 HTTP 客户端
 * Feign 原理：通过 JDK 动态代理，将接口方法映射为 HTTP 请求
 * Feign + LoadBalancer 实现客户端负载均衡
 */
@SpringBootApplication(scanBasePackages = "com.minipay")
@EnableDiscoveryClient
@EnableFeignClients
@MapperScan("com.minipay.order.mapper")
public class OrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
