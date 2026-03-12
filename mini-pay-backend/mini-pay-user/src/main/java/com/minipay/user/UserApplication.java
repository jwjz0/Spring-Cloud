package com.minipay.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * 用户服务启动类
 *
 * 面试考点：
 * 1. @SpringBootApplication(scanBasePackages = "com.minipay") = @Configuration + @EnableAutoConfiguration + @ComponentScan
 * 2. @EnableDiscoveryClient 让服务注册到 Nacos
 * 3. @MapperScan 扫描 MyBatis Mapper 接口，生成代理对象注入 Spring 容器
 */
@SpringBootApplication(scanBasePackages = "com.minipay")
@EnableDiscoveryClient
@MapperScan("com.minipay.user.mapper")
public class UserApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
