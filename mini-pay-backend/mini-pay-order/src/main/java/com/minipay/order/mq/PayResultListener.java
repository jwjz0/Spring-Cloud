package com.minipay.order.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minipay.order.service.OrderService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 支付结果消费者
 *
 * 面试考点 - 为什么支付结果用 MQ 通知而不是同步调用？
 * 1. 解耦：支付服务不需要知道订单服务的存在
 * 2. 异步：支付完成后异步更新订单，不阻塞支付响应
 * 3. 削峰：高并发支付时，MQ 缓冲消息，订单服务按自己的速度消费
 * 4. 可靠：MQ 保证消息不丢失（持久化 + 重试机制）
 */
@Component
@RocketMQMessageListener(
        topic = "pay-result-topic",
        consumerGroup = "pay-result-consumer-group"
)
public class PayResultListener implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(PayResultListener.class);

    private final OrderService orderService;
    private final ObjectMapper objectMapper;

    public PayResultListener(OrderService orderService, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(String payload) {
        log.info("收到支付成功消息, payload={}", payload);
        try {
            orderService.markAsPaidByMq(extractOrderId(payload));
        } catch (Exception e) {
            log.error("处理支付结果失败, payload={}", payload, e);
            throw e;
        }
    }

    private Long extractOrderId(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("支付结果消息为空");
        }
        String trimmed = payload.trim();
        if (trimmed.startsWith("{")) {
            try {
                JsonNode root = objectMapper.readTree(trimmed);
                JsonNode orderIdNode = root.get("orderId");
                if (orderIdNode == null || orderIdNode.isNull()) {
                    throw new IllegalArgumentException("支付结果消息缺少 orderId");
                }
                return orderIdNode.asLong();
            } catch (Exception e) {
                throw new IllegalArgumentException("支付结果消息解析失败: " + trimmed, e);
            }
        }
        return Long.parseLong(trimmed);
    }
}
