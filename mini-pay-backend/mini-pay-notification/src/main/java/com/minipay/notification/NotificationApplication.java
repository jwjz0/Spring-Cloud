package com.minipay.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * 通知服务
 *
 * 面试考点 - Kafka vs RocketMQ 选型：
 * | 维度         | Kafka          | RocketMQ          |
 * |-------------|----------------|-------------------|
 * | 吞吐量       | 极高（百万级TPS） | 高（十万级TPS）     |
 * | 延迟消息      | 不支持原生       | 支持 18 个延迟级别   |
 * | 事务消息      | 支持（较复杂）    | 原生支持（半消息+回查）|
 * | 消息回溯      | 基于 offset     | 基于时间戳          |
 * | 适用场景      | 日志、大数据流    | 业务消息、电商交易    |
 *
 * 本项目：RocketMQ 用于支付通知（业务消息），Kafka 用于支付流水日志（大数据场景）
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableKafka
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
