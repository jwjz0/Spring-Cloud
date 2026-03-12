package com.minipay.pay.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.minipay.common.dto.TraceResult;
import com.minipay.common.dto.TraceStep;
import com.minipay.common.entity.Order;
import com.minipay.common.entity.OrderTrace;
import com.minipay.common.entity.PayRecord;
import com.minipay.common.result.R;
import com.minipay.pay.feign.OrderFeignClient;
import com.minipay.pay.mapper.OrderTraceMapper;
import com.minipay.pay.mapper.PayRecordMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 支付服务
 *
 * 面试考点 - 支付幂等性：
 * 1. 同一订单不能重复支付 → Redis 分布式锁
 * 2. 支付成功后通过 RocketMQ 通知订单服务 → 异步解耦
 * 3. 为什么不用数据库唯一索引做幂等？→ 可以，但分布式锁更灵活，还能控制并发
 */
@Service
public class PayService {

    private static final Logger log = LoggerFactory.getLogger(PayService.class);

    private final PayRecordMapper payRecordMapper;
    private final OrderTraceMapper orderTraceMapper;
    private final OrderFeignClient orderFeignClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate redisTemplate;

    public PayService(PayRecordMapper payRecordMapper,
                      OrderTraceMapper orderTraceMapper,
                      OrderFeignClient orderFeignClient,
                      KafkaTemplate<String, String> kafkaTemplate,
                      RocketMQTemplate rocketMQTemplate,
                      StringRedisTemplate redisTemplate) {
        this.payRecordMapper = payRecordMapper;
        this.orderTraceMapper = orderTraceMapper;
        this.orderFeignClient = orderFeignClient;
        this.kafkaTemplate = kafkaTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 创建支付（带调用链追踪）
     */
    public TraceResult<PayRecord> createPay(Long userId, Long orderId, String channel) {
        TraceResult<PayRecord> result = new TraceResult<>();
        long start, stepStart;
        boolean lockAcquired = false;
        start = System.currentTimeMillis();

        // 1. 参数校验
        stepStart = System.currentTimeMillis();
        if (channel == null || channel.isBlank()) {
            throw new RuntimeException("支付渠道不能为空");
        }
        result.addStep("pay-service", "业务参数校验", "业务校验",
                String.format("userId=%s, orderId=%s, channel=%s", userId, orderId, channel),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 2. Redis 分布式锁 —— 防止同一订单重复支付
        stepStart = System.currentTimeMillis();
        String lockKey = "pay:lock:" + orderId;
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "1", 30, TimeUnit.SECONDS);

        if (locked == null || !locked) {
            result.addStep("pay-service", "支付请求被拒绝（重复提交）", "Redis 分布式锁",
                    "同一订单存在并发支付请求，未获取到锁，直接拒绝", "fail",
                    System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            appendTrace(orderId, result.getTrace());
            throw new RuntimeException("支付处理中，请勿重复提交");
        }
        lockAcquired = true;
        result.addStep("pay-service", "Redis SETNX 获取分布式锁", "Redis",
                String.format("key=%s，SETNX 成功并设置 30 秒过期（防死锁）", lockKey),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        try {
            // 3. 检查是否已有成功的支付记录（幂等性保证）
            stepStart = System.currentTimeMillis();
            PayRecord existing = payRecordMapper.selectOne(
                    new LambdaQueryWrapper<PayRecord>()
                            .eq(PayRecord::getOrderId, orderId)
                            .eq(PayRecord::getStatus, PayRecord.STATUS_SUCCESS));
            if (existing != null) {
                throw new RuntimeException("该订单已支付");
            }
            result.addStep("pay-service", "MySQL 查询支付记录（幂等校验）", "MyBatis Plus",
                    "查询是否存在成功的支付记录，防止重复支付。面试考点：幂等性设计",
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

            // 4. Feign 调用订单服务查询订单
            stepStart = System.currentTimeMillis();
            R<Order> orderResult = orderFeignClient.getOrder(orderId);
            if (orderResult.getCode() != 200 || orderResult.getData() == null) {
                throw new RuntimeException("订单不存在");
            }
            Order order = orderResult.getData();
            result.addStep("pay-service", "订单状态校验", "订单状态机",
                    String.format("查询到订单：orderId=%s, orderStatus=%s, amount=%s",
                            order.getId(), order.getStatus(), order.getAmount()),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            if (order.getStatus() != Order.STATUS_PENDING) {
                throw new RuntimeException("订单状态异常，无法支付");
            }
            result.addStep("pay-service → order-service", "Feign 远程调用查询订单", "OpenFeign + Nacos",
                    "通过 Nacos 服务发现找到 order-service 实例，Feign 发起 HTTP 调用。面试考点：服务注册与发现",
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

            // 5. 创建支付记录
            stepStart = System.currentTimeMillis();
            PayRecord payRecord = new PayRecord();
            payRecord.setOrderId(orderId);
            payRecord.setUserId(userId);
            payRecord.setAmount(order.getAmount());
            payRecord.setChannel(channel);
            payRecord.setStatus(PayRecord.STATUS_PROCESSING);
            payRecord.setCreateTime(LocalDateTime.now());
            String outTradeNo = "PAY" + System.currentTimeMillis() + orderId;
            payRecord.setOutTradeNo(outTradeNo);
            payRecordMapper.insert(payRecord);
            result.addStep("pay-service", "MySQL 插入支付记录（处理中）", "MyBatis Plus",
                    String.format("payId=%s, orderId=%s, amount=%s, channel=%s, outTradeNo=%s",
                            payRecord.getId(), orderId, payRecord.getAmount(), channel, outTradeNo),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

            // 6. 通知订单服务进入支付中
            stepStart = System.currentTimeMillis();
            try {
                orderFeignClient.markPaying(orderId);
                result.addStep("pay-service → order-service", "Feign 调用标记订单为支付中", "OpenFeign + 订单状态机",
                        String.format("orderId=%s, PENDING → PAYING", orderId),
                        "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            } catch (Exception e) {
                log.warn("标记订单支付中失败, orderId={}, error={}", orderId, e.getMessage());
                result.addStep("pay-service → order-service", "标记订单支付中失败（不影响支付单创建）", "OpenFeign",
                        e.getMessage(), "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            }

            // 7. 总耗时
            result.addStep("pay-service", "支付单创建完成，等待渠道回调", "全链路",
                    String.format("outTradeNo=%s, orderId=%s, 支付单已创建（PROCESSING），等待渠道异步回调驱动状态", outTradeNo, orderId),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - start);

            result.setData(payRecord);
            return result;
        } catch (RuntimeException e) {
            result.addStep("pay-service", "支付流程失败", "异常处理",
                    e.getMessage(), "fail",
                    System.currentTimeMillis(), System.currentTimeMillis() - start);
            throw e;
        } finally {
            if (lockAcquired) {
                stepStart = System.currentTimeMillis();
                Boolean deleted = redisTemplate.delete(lockKey);
                result.addStep("pay-service", "释放分布式锁", "Redis DEL",
                        String.format("key=%s, deleted=%s", lockKey, deleted),
                        "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            }
            appendTrace(orderId, result.getTrace());
        }
    }

    /**
     * 查询支付状态
     */
    public PayRecord queryPay(Long payId) {
        return payRecordMapper.selectById(payId);
    }

    private String buildPayLogMessage(PayRecord payRecord) {
        return String.format(
                "{\"event\":\"PAY_SUCCESS\",\"payId\":\"%s\",\"orderId\":\"%s\",\"userId\":\"%s\",\"amount\":\"%s\",\"channel\":\"%s\",\"finishTime\":\"%s\"}",
                payRecord.getId(),
                payRecord.getOrderId(),
                payRecord.getUserId(),
                payRecord.getAmount(),
                payRecord.getChannel(),
                payRecord.getFinishTime()
        );
    }

    private void appendTrace(Long orderId, List<TraceStep> steps) {
        if (orderId == null || steps == null || steps.isEmpty()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            for (int i = 0; i < steps.size(); i++) {
                TraceStep step = steps.get(i);
                OrderTrace row = new OrderTrace();
                row.setOrderId(orderId);
                row.setStepNo(i + 1);
                row.setService(step.getService());
                row.setAction(step.getAction());
                row.setTech(step.getTech());
                row.setDetail(step.getDetail());
                row.setStatus(step.getStatus());
                row.setStepTimestamp(step.getTimestamp());
                row.setDuration(step.getDuration());
                row.setCreateTime(now);
                orderTraceMapper.insert(row);
            }
        } catch (Exception e) {
            log.warn("写入支付调用链失败, orderId={}, error={}", orderId, e.getMessage());
        }
    }
}
