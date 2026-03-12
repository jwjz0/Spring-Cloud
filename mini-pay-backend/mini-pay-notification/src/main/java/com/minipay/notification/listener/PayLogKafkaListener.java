package com.minipay.notification.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka 消费者 - 支付流水日志
 *
 * 面试考点 - Kafka 核心概念：
 * 1. Topic: 消息主题，逻辑分类
 * 2. Partition: 分区，实现并行消费。同一 partition 内消息有序
 * 3. Consumer Group: 消费组，组内消费者负载均衡，组间广播消费
 * 4. Offset: 消费位移，记录消费到哪条消息了
 *
 * Kafka 高吞吐原理：
 * 1. 顺序写磁盘（磁盘顺序写比内存随机写还快）
 * 2. 零拷贝（sendfile 系统调用，数据不经过用户态）
 * 3. 批量发送 + 压缩
 * 4. 分区并行
 */
@Component
public class PayLogKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(PayLogKafkaListener.class);

    @KafkaListener(topics = "pay-log-topic", groupId = "pay-log-consumer-group")
    public void onPayLog(String message) {
        log.info("【Kafka】收到支付流水日志: {}", message);
        // 实际场景：写入 Elasticsearch / 大数据平台 / 风控系统
    }
}
