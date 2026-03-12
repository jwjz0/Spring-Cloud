package com.minipay.order.mq;

import com.minipay.order.service.OrderService;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 订单超时消费者
 *
 * 面试考点 - RocketMQ 消费者：
 * 1. @RocketMQMessageListener 声明消费者，指定 topic 和 consumerGroup
 * 2. consumerGroup 相同的消费者实例负载均衡消费（集群模式）
 * 3. 消费失败会重试（默认重试 16 次），超过重试次数进入死信队列
 * 4. 消费逻辑必须保证幂等性（先检查状态再操作）
 */
@Component
@RocketMQMessageListener(
        topic = "order-timeout-topic",
        consumerGroup = "order-timeout-consumer-group"
)
public class OrderTimeoutListener implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutListener.class);

    private final OrderService orderService;

    public OrderTimeoutListener(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public void onMessage(String orderId) {
        log.info("收到订单超时消息, orderId={}", orderId);
        try {
            orderService.closeTimeoutOrder(Long.parseLong(orderId));
        } catch (Exception e) {
            log.error("处理订单超时失败, orderId={}", orderId, e);
            throw e; // 抛出异常触发 RocketMQ 重试
        }
    }
}
