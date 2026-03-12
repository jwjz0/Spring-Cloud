package com.minipay.order.mq;

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

    public PayResultListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public void onMessage(String orderId) {
        log.info("收到支付成功消息, orderId={}", orderId);
        try {
            orderService.markAsPaidByMq(Long.parseLong(orderId));
        } catch (Exception e) {
            log.error("处理支付结果失败, orderId={}", orderId, e);
            throw e;
        }
    }
}
