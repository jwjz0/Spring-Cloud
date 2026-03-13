package com.minipay.pay.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.minipay.common.dto.PayCallbackRequest;
import com.minipay.common.dto.RefundCallbackRequest;
import com.minipay.common.dto.RefundRequest;
import com.minipay.common.dto.TraceResult;
import com.minipay.common.dto.TraceStep;
import com.minipay.common.entity.IdempotentRecord;
import com.minipay.common.entity.Order;
import com.minipay.common.entity.OrderTrace;
import com.minipay.common.entity.PayRecord;
import com.minipay.common.entity.RefundRecord;
import com.minipay.common.result.R;
import com.minipay.common.util.SignUtil;
import com.minipay.pay.feign.OrderFeignClient;
import com.minipay.pay.mapper.IdempotentRecordMapper;
import com.minipay.pay.mapper.OrderTraceMapper;
import com.minipay.pay.mapper.PayRecordMapper;
import com.minipay.pay.mapper.RefundRecordMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    private static final String SIGN_SECRET = "MiniPayCallbackSecret2026";

    private final PayRecordMapper payRecordMapper;
    private final OrderTraceMapper orderTraceMapper;
    private final OrderFeignClient orderFeignClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final StringRedisTemplate redisTemplate;
    private final IdempotentRecordMapper idempotentRecordMapper;
    private final RefundRecordMapper refundRecordMapper;

    public PayService(PayRecordMapper payRecordMapper,
                      OrderTraceMapper orderTraceMapper,
                      OrderFeignClient orderFeignClient,
                      KafkaTemplate<String, String> kafkaTemplate,
                      RocketMQTemplate rocketMQTemplate,
                      StringRedisTemplate redisTemplate,
                      IdempotentRecordMapper idempotentRecordMapper,
                      RefundRecordMapper refundRecordMapper) {
        this.payRecordMapper = payRecordMapper;
        this.orderTraceMapper = orderTraceMapper;
        this.orderFeignClient = orderFeignClient;
        this.kafkaTemplate = kafkaTemplate;
        this.rocketMQTemplate = rocketMQTemplate;
        this.redisTemplate = redisTemplate;
        this.idempotentRecordMapper = idempotentRecordMapper;
        this.refundRecordMapper = refundRecordMapper;
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

    // ======================== Task 2: 支付回调处理 ========================

    /**
     * 处理支付渠道回调（含验签、幂等、状态流转）
     */
    public TraceResult<PayRecord> handlePayCallback(PayCallbackRequest req) {
        TraceResult<PayRecord> result = new TraceResult<>();
        long start = System.currentTimeMillis();
        long stepStart;

        // 1. 验签
        stepStart = System.currentTimeMillis();
        Map<String, Object> signParams = new TreeMap<>();
        signParams.put("outTradeNo", req.getOutTradeNo());
        signParams.put("channelTxnNo", req.getChannelTxnNo());
        signParams.put("payStatus", req.getPayStatus());
        signParams.put("paidAmount", req.getPaidAmount().toPlainString());
        signParams.put("callbackTimestamp", req.getCallbackTimestamp());
        signParams.put("nonce", req.getNonce());
        boolean signOk = SignUtil.verifyMap(signParams, SIGN_SECRET, req.getSign());
        if (!signOk) {
            result.addStep("pay-service", "支付回调验签失败", "SignUtil HmacSHA256",
                    String.format("outTradeNo=%s, 签名不匹配", req.getOutTradeNo()),
                    "fail", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            throw new RuntimeException("支付回调验签失败");
        }
        result.addStep("pay-service", "支付回调验签通过", "SignUtil HmacSHA256",
                String.format("outTradeNo=%s, channelTxnNo=%s", req.getOutTradeNo(), req.getChannelTxnNo()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 2. 幂等校验（数据库唯一索引）
        stepStart = System.currentTimeMillis();
        String bizKey = req.getOutTradeNo() + ":" + req.getChannelTxnNo();
        IdempotentRecord idempotentRecord = new IdempotentRecord();
        idempotentRecord.setBizType("PAY_CALLBACK");
        idempotentRecord.setBizKey(bizKey);
        idempotentRecord.setStatus(IdempotentRecord.STATUS_PROCESSING);
        idempotentRecord.setCreateTime(LocalDateTime.now());
        try {
            idempotentRecordMapper.insert(idempotentRecord);
        } catch (Exception e) {
            // 唯一索引冲突 → 重复回调
            result.addStep("pay-service", "支付回调幂等拦截（重复回调）", "MySQL 唯一索引",
                    String.format("bizKey=%s, 已存在幂等记录，直接返回", bizKey),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            PayRecord existingPay = payRecordMapper.selectOne(
                    new LambdaQueryWrapper<PayRecord>()
                            .eq(PayRecord::getOutTradeNo, req.getOutTradeNo())
                            .last("LIMIT 1"));
            result.setData(existingPay);
            return result;
        }
        result.addStep("pay-service", "幂等记录插入成功", "MySQL 唯一索引",
                String.format("bizType=PAY_CALLBACK, bizKey=%s", bizKey),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 3. 查询支付记录
        stepStart = System.currentTimeMillis();
        PayRecord payRecord = payRecordMapper.selectOne(
                new LambdaQueryWrapper<PayRecord>()
                        .eq(PayRecord::getOutTradeNo, req.getOutTradeNo())
                        .last("LIMIT 1"));
        if (payRecord == null) {
            result.addStep("pay-service", "支付记录不存在", "MySQL",
                    String.format("outTradeNo=%s", req.getOutTradeNo()),
                    "fail", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            throw new RuntimeException("支付记录不存在: outTradeNo=" + req.getOutTradeNo());
        }
        result.addStep("pay-service", "查询支付记录", "MyBatis Plus",
                String.format("payId=%s, orderId=%s, currentStatus=%s", payRecord.getId(), payRecord.getOrderId(), payRecord.getStatus()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        Long orderId = payRecord.getOrderId();

        // 4. 终态检查
        if (payRecord.getStatus() == PayRecord.STATUS_SUCCESS || payRecord.getStatus() == PayRecord.STATUS_FAIL) {
            result.addStep("pay-service", "支付记录已为终态，跳过处理", "状态机",
                    String.format("payId=%s, status=%s", payRecord.getId(), payRecord.getStatus()),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            result.setData(payRecord);
            appendTrace(orderId, result.getTrace());
            return result;
        }

        // 5. SUCCESS 回调
        if ("SUCCESS".equals(req.getPayStatus())) {
            stepStart = System.currentTimeMillis();
            payRecord.setStatus(PayRecord.STATUS_SUCCESS);
            payRecord.setChannelTxnNo(req.getChannelTxnNo());
            payRecord.setCallbackTime(LocalDateTime.now());
            payRecord.setCallbackRaw(req.getRawBody());
            payRecord.setFinishTime(LocalDateTime.now());
            payRecord.setUpdateTime(LocalDateTime.now());
            payRecordMapper.updateById(payRecord);
            result.addStep("pay-service", "更新支付记录为 SUCCESS", "MyBatis Plus",
                    String.format("payId=%s, channelTxnNo=%s", payRecord.getId(), req.getChannelTxnNo()),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

            // Kafka 日志
            stepStart = System.currentTimeMillis();
            try {
                String logMsg = buildPayLogMessage(payRecord);
                kafkaTemplate.send("pay-log-topic", String.valueOf(payRecord.getId()), logMsg);
                result.addStep("pay-service → Kafka", "发送支付成功日志", "Kafka",
                        String.format("topic=pay-log-topic, payId=%s", payRecord.getId()),
                        "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            } catch (Exception e) {
                log.warn("Kafka 发送支付日志失败, payId={}, error={}", payRecord.getId(), e.getMessage());
                result.addStep("pay-service → Kafka", "发送支付日志失败（不影响主流程）", "Kafka",
                        e.getMessage(), "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            }

            // Feign 同步更新订单状态（主路径）
            stepStart = System.currentTimeMillis();
            try {
                orderFeignClient.markPaid(orderId, req.getChannelTxnNo());
                result.addStep("pay-service → order-service", "Feign 调用 markPaid 成功", "OpenFeign",
                        String.format("orderId=%s, channelTxnNo=%s", orderId, req.getChannelTxnNo()),
                        "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            } catch (Exception e) {
                log.warn("Feign 调用 markPaid 失败, orderId={}, error={}", orderId, e.getMessage());
                result.addStep("pay-service → order-service", "Feign 调用 markPaid 失败", "OpenFeign",
                        e.getMessage(), "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            }

            // RocketMQ 异步通知（用于其他消费者，如通知服务）
            stepStart = System.currentTimeMillis();
            try {
                String mqMsg = String.format("{\"orderId\":%s,\"payId\":%s,\"channelTxnNo\":\"%s\"}",
                        orderId, payRecord.getId(), req.getChannelTxnNo());
                rocketMQTemplate.convertAndSend("pay-result-topic", mqMsg);
                result.addStep("pay-service → RocketMQ", "发送支付结果消息（异步通知）", "RocketMQ",
                        String.format("topic=pay-result-topic, orderId=%s", orderId),
                        "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            } catch (Exception e) {
                log.warn("RocketMQ 发送失败（不影响主流程）, orderId={}, error={}", orderId, e.getMessage());
                result.addStep("pay-service → RocketMQ", "MQ 发送失败（不影响主流程）", "RocketMQ",
                        e.getMessage(), "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            }
        }

        // 6. FAIL 回调
        if ("FAIL".equals(req.getPayStatus())) {
            stepStart = System.currentTimeMillis();
            payRecord.setStatus(PayRecord.STATUS_FAIL);
            payRecord.setCallbackTime(LocalDateTime.now());
            payRecord.setCallbackRaw(req.getRawBody());
            payRecord.setFinishTime(LocalDateTime.now());
            payRecord.setUpdateTime(LocalDateTime.now());
            payRecordMapper.updateById(payRecord);
            result.addStep("pay-service", "更新支付记录为 FAIL", "MyBatis Plus",
                    String.format("payId=%s, reason=%s", payRecord.getId(), req.getFailReason()),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

            stepStart = System.currentTimeMillis();
            try {
                orderFeignClient.markPayFailed(orderId, req.getFailReason());
                result.addStep("pay-service → order-service", "Feign 调用 markPayFailed", "OpenFeign",
                        String.format("orderId=%s", orderId),
                        "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            } catch (Exception e) {
                log.warn("Feign 调用 markPayFailed 失败, orderId={}, error={}", orderId, e.getMessage());
                result.addStep("pay-service → order-service", "Feign 调用 markPayFailed 失败", "OpenFeign",
                        e.getMessage(), "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            }
        }

        // 7. 更新幂等记录为成功
        stepStart = System.currentTimeMillis();
        idempotentRecord.setStatus(IdempotentRecord.STATUS_SUCCESS);
        idempotentRecord.setUpdateTime(LocalDateTime.now());
        idempotentRecordMapper.updateById(idempotentRecord);
        result.addStep("pay-service", "更新幂等记录为 SUCCESS", "MySQL",
                String.format("bizKey=%s", bizKey),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 8. 完成
        result.addStep("pay-service", "支付回调处理完成", "全链路",
                String.format("outTradeNo=%s, orderId=%s, 总耗时=%dms", req.getOutTradeNo(), orderId, System.currentTimeMillis() - start),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - start);

        result.setData(payRecord);
        appendTrace(orderId, result.getTrace());
        return result;
    }

    // ======================== Task 3: 退款申请 ========================

    /**
     * 退款申请
     */
    public TraceResult<RefundRecord> applyRefund(Long userId, RefundRequest req) {
        TraceResult<RefundRecord> result = new TraceResult<>();
        long start = System.currentTimeMillis();
        long stepStart;

        Long orderId = req.getOrderId();

        // 1. 查询成功的支付记录
        stepStart = System.currentTimeMillis();
        PayRecord payRecord = payRecordMapper.selectOne(
                new LambdaQueryWrapper<PayRecord>()
                        .eq(PayRecord::getOrderId, orderId)
                        .eq(PayRecord::getStatus, PayRecord.STATUS_SUCCESS)
                        .last("LIMIT 1"));
        if (payRecord == null) {
            result.addStep("pay-service", "未找到成功的支付记录", "MySQL",
                    String.format("orderId=%s, 无法退款", orderId),
                    "fail", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            appendTrace(orderId, result.getTrace());
            throw new RuntimeException("未找到成功的支付记录，无法退款: orderId=" + orderId);
        }
        result.addStep("pay-service", "查询成功支付记录", "MyBatis Plus",
                String.format("payId=%s, orderId=%s, paidAmount=%s", payRecord.getId(), orderId, payRecord.getAmount()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 2. 校验退款金额
        stepStart = System.currentTimeMillis();
        BigDecimal refundAmount = req.getRefundAmount();
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            result.addStep("pay-service", "退款金额校验失败", "业务校验",
                    "退款金额必须大于0", "fail", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            appendTrace(orderId, result.getTrace());
            throw new RuntimeException("退款金额必须大于0");
        }
        if (refundAmount.compareTo(payRecord.getAmount()) > 0) {
            result.addStep("pay-service", "退款金额校验失败", "业务校验",
                    String.format("退款金额 %s 超过已付金额 %s", refundAmount, payRecord.getAmount()),
                    "fail", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            appendTrace(orderId, result.getTrace());
            throw new RuntimeException("退款金额不能超过已付金额");
        }
        result.addStep("pay-service", "退款金额校验通过", "业务校验",
                String.format("refundAmount=%s, paidAmount=%s", refundAmount, payRecord.getAmount()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 3. 创建退款记录
        stepStart = System.currentTimeMillis();
        String refundNo = "REF" + System.currentTimeMillis() + orderId;
        RefundRecord refundRecord = new RefundRecord();
        refundRecord.setRefundNo(refundNo);
        refundRecord.setOrderId(orderId);
        refundRecord.setPayId(payRecord.getId());
        refundRecord.setUserId(userId);
        refundRecord.setAmount(refundAmount);
        refundRecord.setReason(req.getReason());
        refundRecord.setStatus(RefundRecord.STATUS_PROCESSING);
        refundRecord.setCreateTime(LocalDateTime.now());
        refundRecordMapper.insert(refundRecord);
        result.addStep("pay-service", "创建退款记录（PROCESSING）", "MyBatis Plus",
                String.format("refundNo=%s, orderId=%s, amount=%s", refundNo, orderId, refundAmount),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 4. Feign 通知订单服务进入退款中
        stepStart = System.currentTimeMillis();
        try {
            orderFeignClient.markRefunding(orderId, req.getReason());
            result.addStep("pay-service → order-service", "Feign 调用 markRefunding", "OpenFeign",
                    String.format("orderId=%s, reason=%s", orderId, req.getReason()),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        } catch (Exception e) {
            log.warn("Feign 调用 markRefunding 失败, orderId={}, error={}", orderId, e.getMessage());
            result.addStep("pay-service → order-service", "Feign 调用 markRefunding 失败", "OpenFeign",
                    e.getMessage(), "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        }

        // 5. 完成
        result.addStep("pay-service", "退款申请完成", "全链路",
                String.format("refundNo=%s, orderId=%s, 总耗时=%dms", refundNo, orderId, System.currentTimeMillis() - start),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - start);

        result.setData(refundRecord);
        appendTrace(orderId, result.getTrace());
        return result;
    }

    // ======================== Task 4: 退款回调 + 查询 ========================

    /**
     * 处理退款渠道回调（含验签、幂等、状态流转）
     */
    public TraceResult<RefundRecord> handleRefundCallback(RefundCallbackRequest req) {
        TraceResult<RefundRecord> result = new TraceResult<>();
        long start = System.currentTimeMillis();
        long stepStart;

        // 1. 验签
        stepStart = System.currentTimeMillis();
        Map<String, Object> signParams = new TreeMap<>();
        signParams.put("refundNo", req.getRefundNo());
        signParams.put("channelRefundNo", req.getChannelRefundNo());
        signParams.put("refundStatus", req.getRefundStatus());
        signParams.put("refundAmount", req.getRefundAmount().toPlainString());
        signParams.put("callbackTimestamp", req.getCallbackTimestamp());
        signParams.put("nonce", req.getNonce());
        boolean signOk = SignUtil.verifyMap(signParams, SIGN_SECRET, req.getSign());
        if (!signOk) {
            result.addStep("pay-service", "退款回调验签失败", "SignUtil HmacSHA256",
                    String.format("refundNo=%s, 签名不匹配", req.getRefundNo()),
                    "fail", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            throw new RuntimeException("退款回调验签失败");
        }
        result.addStep("pay-service", "退款回调验签通过", "SignUtil HmacSHA256",
                String.format("refundNo=%s, channelRefundNo=%s", req.getRefundNo(), req.getChannelRefundNo()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 2. 幂等校验
        stepStart = System.currentTimeMillis();
        String bizKey = req.getRefundNo() + ":" + req.getChannelRefundNo();
        IdempotentRecord idempotentRecord = new IdempotentRecord();
        idempotentRecord.setBizType("REFUND_CALLBACK");
        idempotentRecord.setBizKey(bizKey);
        idempotentRecord.setStatus(IdempotentRecord.STATUS_PROCESSING);
        idempotentRecord.setCreateTime(LocalDateTime.now());
        try {
            idempotentRecordMapper.insert(idempotentRecord);
        } catch (Exception e) {
            result.addStep("pay-service", "退款回调幂等拦截（重复回调）", "MySQL 唯一索引",
                    String.format("bizKey=%s, 已存在幂等记录，直接返回", bizKey),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            RefundRecord existingRefund = refundRecordMapper.selectOne(
                    new LambdaQueryWrapper<RefundRecord>()
                            .eq(RefundRecord::getRefundNo, req.getRefundNo())
                            .last("LIMIT 1"));
            result.setData(existingRefund);
            return result;
        }
        result.addStep("pay-service", "退款幂等记录插入成功", "MySQL 唯一索引",
                String.format("bizType=REFUND_CALLBACK, bizKey=%s", bizKey),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 3. 查询退款记录
        stepStart = System.currentTimeMillis();
        RefundRecord refundRecord = refundRecordMapper.selectOne(
                new LambdaQueryWrapper<RefundRecord>()
                        .eq(RefundRecord::getRefundNo, req.getRefundNo())
                        .last("LIMIT 1"));
        if (refundRecord == null) {
            result.addStep("pay-service", "退款记录不存在", "MySQL",
                    String.format("refundNo=%s", req.getRefundNo()),
                    "fail", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            throw new RuntimeException("退款记录不存在: refundNo=" + req.getRefundNo());
        }
        result.addStep("pay-service", "查询退款记录", "MyBatis Plus",
                String.format("refundId=%s, orderId=%s, currentStatus=%s", refundRecord.getId(), refundRecord.getOrderId(), refundRecord.getStatus()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        Long orderId = refundRecord.getOrderId();

        // 4. 终态检查
        if (refundRecord.getStatus() == RefundRecord.STATUS_SUCCESS || refundRecord.getStatus() == RefundRecord.STATUS_FAIL) {
            result.addStep("pay-service", "退款记录已为终态，跳过处理", "状态机",
                    String.format("refundId=%s, status=%s", refundRecord.getId(), refundRecord.getStatus()),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            result.setData(refundRecord);
            appendTrace(orderId, result.getTrace());
            return result;
        }

        // 5. SUCCESS 回调
        if ("SUCCESS".equals(req.getRefundStatus())) {
            stepStart = System.currentTimeMillis();
            refundRecord.setStatus(RefundRecord.STATUS_SUCCESS);
            refundRecord.setChannelRefundNo(req.getChannelRefundNo());
            refundRecord.setCallbackTime(LocalDateTime.now());
            refundRecord.setCallbackRaw(req.getRawBody());
            refundRecord.setFinishTime(LocalDateTime.now());
            refundRecord.setUpdateTime(LocalDateTime.now());
            refundRecordMapper.updateById(refundRecord);
            result.addStep("pay-service", "更新退款记录为 SUCCESS", "MyBatis Plus",
                    String.format("refundId=%s, channelRefundNo=%s", refundRecord.getId(), req.getChannelRefundNo()),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

            stepStart = System.currentTimeMillis();
            try {
                orderFeignClient.markRefunded(orderId, req.getRefundNo());
                result.addStep("pay-service → order-service", "Feign 调用 markRefunded", "OpenFeign",
                        String.format("orderId=%s, refundNo=%s", orderId, req.getRefundNo()),
                        "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            } catch (Exception e) {
                log.warn("Feign 调用 markRefunded 失败, orderId={}, error={}", orderId, e.getMessage());
                result.addStep("pay-service → order-service", "Feign 调用 markRefunded 失败", "OpenFeign",
                        e.getMessage(), "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            }
        }

        // 6. FAIL 回调
        if ("FAIL".equals(req.getRefundStatus())) {
            stepStart = System.currentTimeMillis();
            refundRecord.setStatus(RefundRecord.STATUS_FAIL);
            refundRecord.setCallbackTime(LocalDateTime.now());
            refundRecord.setCallbackRaw(req.getRawBody());
            refundRecord.setFinishTime(LocalDateTime.now());
            refundRecord.setUpdateTime(LocalDateTime.now());
            refundRecordMapper.updateById(refundRecord);
            result.addStep("pay-service", "更新退款记录为 FAIL", "MyBatis Plus",
                    String.format("refundId=%s, reason=%s", refundRecord.getId(), req.getFailReason()),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        }

        // 7. 更新幂等记录
        stepStart = System.currentTimeMillis();
        idempotentRecord.setStatus(IdempotentRecord.STATUS_SUCCESS);
        idempotentRecord.setUpdateTime(LocalDateTime.now());
        idempotentRecordMapper.updateById(idempotentRecord);
        result.addStep("pay-service", "更新退款幂等记录为 SUCCESS", "MySQL",
                String.format("bizKey=%s", bizKey),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        result.addStep("pay-service", "退款回调处理完成", "全链路",
                String.format("refundNo=%s, orderId=%s, 总耗时=%dms", req.getRefundNo(), orderId, System.currentTimeMillis() - start),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - start);

        result.setData(refundRecord);
        appendTrace(orderId, result.getTrace());
        return result;
    }

    /**
     * 根据订单ID查询退款记录列表
     */
    public List<RefundRecord> listRefundsByOrderId(Long orderId) {
        return refundRecordMapper.selectList(
                new LambdaQueryWrapper<RefundRecord>()
                        .eq(RefundRecord::getOrderId, orderId)
                        .orderByDesc(RefundRecord::getCreateTime));
    }

    /**
     * 根据订单ID查询最新支付记录
     */
    public PayRecord queryByOrderId(Long orderId) {
        return payRecordMapper.selectOne(
                new LambdaQueryWrapper<PayRecord>()
                        .eq(PayRecord::getOrderId, orderId)
                        .orderByDesc(PayRecord::getCreateTime)
                        .last("LIMIT 1"));
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
