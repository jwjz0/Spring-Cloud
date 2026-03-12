package com.minipay.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.minipay.common.dto.CreateOrderRequest;
import com.minipay.common.dto.TraceResult;
import com.minipay.common.dto.TraceStep;
import com.minipay.common.entity.Order;
import com.minipay.common.entity.OrderTrace;
import com.minipay.common.entity.Product;
import com.minipay.common.result.R;
import com.minipay.order.feign.ProductFeignClient;
import com.minipay.order.mapper.OrderMapper;
import com.minipay.order.mapper.OrderTraceMapper;
import com.minipay.order.mapper.TestDataMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单服务
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderMapper orderMapper;
    private final OrderTraceMapper orderTraceMapper;
    private final TestDataMapper testDataMapper;
    private final ProductFeignClient productFeignClient;
    private final RocketMQTemplate rocketMQTemplate;

    public OrderService(OrderMapper orderMapper,
                        OrderTraceMapper orderTraceMapper,
                        TestDataMapper testDataMapper,
                        ProductFeignClient productFeignClient,
                        RocketMQTemplate rocketMQTemplate) {
        this.orderMapper = orderMapper;
        this.orderTraceMapper = orderTraceMapper;
        this.testDataMapper = testDataMapper;
        this.productFeignClient = productFeignClient;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    /**
     * 创建订单（带调用链追踪）
     */
    public TraceResult<Order> createOrder(Long userId, CreateOrderRequest request) {
        TraceResult<Order> result = new TraceResult<>();
        long start = System.currentTimeMillis();
        long stepStart;

        // 1. Gateway 路由（前端请求已经过 Gateway）
        result.addStep("gateway", "请求路由到 order-service", "Spring Cloud Gateway",
                "Gateway 根据路径 /api/order/** 路由到 order-service，同时 AuthGlobalFilter 验证 JWT Token",
                "success", System.currentTimeMillis(), 0);

        // 2. 参数校验
        stepStart = System.currentTimeMillis();
        if (request.getQuantity() == null || request.getQuantity() < 1) {
            throw new RuntimeException("购买数量必须大于 0");
        }
        result.addStep("order-service", "业务参数校验", "业务校验",
                String.format("userId=%s, productId=%s, quantity=%s",
                        userId, request.getProductId(), request.getQuantity()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 3. Feign 调用商品服务，查询商品信息
        stepStart = System.currentTimeMillis();
        R<Product> productResult = productFeignClient.getProduct(request.getProductId());
        if (productResult.getCode() != 200 || productResult.getData() == null) {
            throw new RuntimeException("商品不存在");
        }
        Product product = productResult.getData();
        result.addStep("order-service → product-service", "Feign 远程调用查询商品", "OpenFeign + Nacos + LoadBalancer",
                String.format("通过 Nacos 发现实例并返回商品：productId=%s, name=%s, price=%s, stock=%s",
                        product.getId(), product.getName(), product.getPrice(), product.getStock()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 4. Feign 调用商品服务，扣减库存
        stepStart = System.currentTimeMillis();
        R<Boolean> deductResult = productFeignClient.deductStock(
                request.getProductId(), request.getQuantity());
        if (deductResult.getCode() != 200) {
            throw new RuntimeException("库存不足");
        }
        result.addStep("order-service → product-service", "Feign 调用扣减库存", "MySQL 乐观锁",
                String.format("扣减成功：productId=%s, quantity=%s。SQL: UPDATE SET stock=stock-? WHERE stock>=?",
                        request.getProductId(), request.getQuantity()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 5. 创建订单
        stepStart = System.currentTimeMillis();
        Order order = new Order();
        order.setUserId(userId);
        order.setProductId(product.getId());
        order.setProductName(product.getName());
        order.setQuantity(request.getQuantity());
        order.setAmount(product.getPrice().multiply(BigDecimal.valueOf(request.getQuantity())));
        order.setStatus(Order.STATUS_PENDING);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.insert(order);
        result.addStep("order-service", "MySQL 写入订单记录", "MyBatis Plus + 雪花算法",
                String.format("生成订单成功：orderId=%s, amount=%s, status=%s",
                        order.getId(), order.getAmount(), order.getStatus()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 6. 发送 RocketMQ 延迟消息 —— 订单超时自动关闭
        stepStart = System.currentTimeMillis();
        try {
            rocketMQTemplate.syncSend("order-timeout-topic",
                    MessageBuilder.withPayload(order.getId().toString()).build(),
                    3000, 16);
            log.info("发送订单超时延迟消息, orderId={}", order.getId());
            result.addStep("order-service → RocketMQ", "发送延迟消息（30分钟后检查超时）", "RocketMQ 延迟消息",
                    "延迟级别 16=30分钟。面试考点：RocketMQ 有 18 个预设延迟级别，不支持任意时间延迟",
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        } catch (Exception e) {
            log.warn("发送延迟消息失败, orderId={}, error={}", order.getId(), e.getMessage());
            result.addStep("order-service → RocketMQ", "延迟消息发送失败（定时任务兜底）", "RocketMQ 延迟消息",
                    "MQ 不可用时，可通过 XXL-Job 定时任务扫描超时订单作为兜底方案",
                    "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        }

        // 7. 总耗时
        result.addStep("order-service", "下单流程完成", "全链路",
                String.format("orderId=%s，总耗时包含：网关路由 → 参数校验 → Feign 查商品 → 乐观锁扣库存 → 写订单 → MQ 延迟消息",
                        order.getId()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - start);

        result.setData(order);
        appendTrace(order.getId(), result.getTrace());
        return result;
    }

    /**
     * 查询用户订单列表
     */
    public List<Order> listByUserId(Long userId) {
        return orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .orderByDesc(Order::getCreateTime));
    }

    /**
     * 查询订单详情
     */
    public Order getById(Long orderId) {
        return orderMapper.selectById(orderId);
    }

    /**
     * 查询某个订单的完整调用链
     */
    public List<TraceStep> getTraceByOrderId(Long userId, Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        if (!order.getUserId().equals(userId)) {
            throw new RuntimeException("无权查看该订单链路");
        }

        List<OrderTrace> traceRows = orderTraceMapper.selectList(
                new LambdaQueryWrapper<OrderTrace>()
                        .eq(OrderTrace::getOrderId, orderId)
                        .orderByAsc(OrderTrace::getStepTimestamp)
                        .orderByAsc(OrderTrace::getId));

        List<TraceStep> steps = new ArrayList<>(traceRows.size());
        for (OrderTrace traceRow : traceRows) {
            steps.add(new TraceStep(
                    traceRow.getService(),
                    traceRow.getAction(),
                    traceRow.getTech(),
                    traceRow.getDetail(),
                    traceRow.getStatus(),
                    traceRow.getStepTimestamp() == null ? 0L : traceRow.getStepTimestamp(),
                    traceRow.getDuration() == null ? 0L : traceRow.getDuration()
            ));
        }
        return steps;
    }

    public void markAsPaidByFeignFallback(Long orderId) {
        markAsPaidByFeignFallback(orderId, null);
    }

    public void markAsPaidByFeignFallback(Long orderId, String channelTxnNo) {
        markAsPaid(orderId,
                "OpenFeign（降级方案）",
                channelTxnNo == null || channelTxnNo.isBlank()
                        ? "MQ 发送失败后，pay-service 通过 Feign 同步调用更新订单状态"
                        : String.format("MQ 发送失败后，pay-service 通过 Feign 同步调用更新订单状态，channelTxnNo=%s", channelTxnNo));
    }

    public void markAsPaidByMq(Long orderId) {
        markAsPaid(orderId,
                "RocketMQ 消费者",
                "消费 pay-result-topic 支付成功消息后更新订单状态");
    }

    public void markAsPaying(Long orderId) {
        updateOrderStatusByStateMachine(
                orderId,
                Order.STATUS_PAYING,
                "更新订单状态为支付中",
                "订单状态机",
                "pay-service 创建支付单后，将订单置为支付中，防止并发重复支付",
                Order.STATUS_PENDING
        );
    }

    public void markAsPayFailed(Long orderId, String reason) {
        String finalReason = (reason == null || reason.isBlank()) ? "支付失败（渠道返回失败）" : reason;
        updateOrderStatusByStateMachine(
                orderId,
                Order.STATUS_PAY_FAILED,
                "更新订单状态为支付失败",
                "订单状态机",
                finalReason,
                Order.STATUS_PENDING, Order.STATUS_PAYING
        );
    }

    public void markAsRefunding(Long orderId, String reason) {
        String detail = (reason == null || reason.isBlank()) ? "已发起退款，订单进入退款中" : reason;
        updateOrderStatusByStateMachine(
                orderId,
                Order.STATUS_REFUNDING,
                "更新订单状态为退款中",
                "订单状态机",
                detail,
                Order.STATUS_PAID
        );
    }

    /**
     * 退款成功后更新订单，并回滚库存
     */
    public void markAsRefunded(Long orderId, String refundNo) {
        long stepStart = System.currentTimeMillis();
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            appendSingleStep(orderId, "order-service", "退款回调处理失败", "订单状态机",
                    "订单不存在，无法更新退款状态", "warn",
                    System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            return;
        }

        Integer currentStatus = order.getStatus();
        if (currentStatus != null && currentStatus == Order.STATUS_REFUNDED) {
            appendSingleStep(orderId, "order-service", "幂等处理：忽略重复退款成功通知", "订单状态机幂等更新",
                    "订单当前已是已退款状态，跳过重复更新", "warn",
                    System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            return;
        }

        // 兼容直接从 PAID 进入退款成功场景，先补一跳到退款中，便于链路回放。
        if (currentStatus != null && currentStatus == Order.STATUS_PAID) {
            order.setStatus(Order.STATUS_REFUNDING);
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);
            appendSingleStep(orderId, "order-service", "自动补偿状态为退款中", "订单状态机",
                    "收到退款成功回调时订单仍为已支付，先补偿迁移到退款中再继续", "success",
                    System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            currentStatus = Order.STATUS_REFUNDING;
        }

        if (currentStatus == null || currentStatus != Order.STATUS_REFUNDING) {
            appendSingleStep(orderId, "order-service", "非法状态迁移（拒绝退款成功）", "订单状态机",
                    String.format("当前状态=%s，目标状态=%s，不允许迁移",
                            statusName(currentStatus), statusName(Order.STATUS_REFUNDED)),
                    "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            return;
        }

        long restoreStepStart = System.currentTimeMillis();
        R<Void> restoreResult = productFeignClient.restoreStock(order.getProductId(), order.getQuantity());
        if (restoreResult.getCode() != 200) {
            appendSingleStep(orderId, "order-service → product-service", "退款回滚库存失败", "OpenFeign + MySQL",
                    String.format("refundNo=%s, productId=%s, quantity=%s, msg=%s",
                            refundNo, order.getProductId(), order.getQuantity(), restoreResult.getMessage()),
                    "fail", System.currentTimeMillis(), System.currentTimeMillis() - restoreStepStart);
            throw new RuntimeException("退款回滚库存失败");
        }
        appendSingleStep(orderId, "order-service → product-service", "退款回滚库存成功", "OpenFeign + MySQL",
                String.format("refundNo=%s, productId=%s, quantity=%s",
                        refundNo, order.getProductId(), order.getQuantity()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - restoreStepStart);

        long statusStepStart = System.currentTimeMillis();
        order.setStatus(Order.STATUS_REFUNDED);
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
        appendSingleStep(orderId, "order-service", "更新订单状态为已退款", "订单状态机",
                String.format("refundNo=%s", refundNo),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - statusStepStart);
    }

    /**
     * 更新订单状态为已支付
     */
    private void markAsPaid(Long orderId, String tech, String detail) {
        updateOrderStatusByStateMachine(
                orderId,
                Order.STATUS_PAID,
                "更新订单状态为已支付",
                tech,
                detail,
                Order.STATUS_PENDING, Order.STATUS_PAYING
        );
    }

    /**
     * 关闭超时未支付订单
     */
    public void closeTimeoutOrder(Long orderId) {
        long stepStart = System.currentTimeMillis();
        Order order = orderMapper.selectById(orderId);
        if (order != null && order.getStatus() == Order.STATUS_PENDING) {
            order.setStatus(Order.STATUS_CLOSED);
            order.setUpdateTime(LocalDateTime.now());
            orderMapper.updateById(order);
            productFeignClient.restoreStock(order.getProductId(), order.getQuantity());
            appendSingleStep(orderId, "order-service", "关闭超时未支付订单并回滚库存", "RocketMQ 延迟消息 + Feign",
                    "消费 order-timeout-topic 后关闭订单，并调用 product-service 恢复库存",
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            log.info("订单超时关闭, orderId={}, 库存已恢复", orderId);
            return;
        }
        appendSingleStep(orderId, "order-service", "忽略超时关单消息（状态不匹配）", "订单状态机幂等更新",
                order == null ? "订单不存在，忽略" : String.format("当前状态=%s，不允许关闭", statusName(order.getStatus())),
                "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
    }

    /**
     * 管理后台统计数据
     */
    public Map<String, Object> getDashboardStats() {
        List<Order> allOrders = orderMapper.selectList(null);
        long totalOrders = allOrders.size();
        long todayOrders = allOrders.stream()
                .filter(o -> o.getCreateTime().toLocalDate().equals(LocalDateTime.now().toLocalDate()))
                .count();
        double totalAmount = allOrders.stream()
                .filter(o -> o.getStatus() == Order.STATUS_PAID)
                .mapToDouble(o -> o.getAmount().doubleValue())
                .sum();
        long paidCount = allOrders.stream().filter(o -> o.getStatus() == Order.STATUS_PAID).count();
        double successRate = totalOrders > 0 ? (double) paidCount / totalOrders * 100 : 0;

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalOrders", totalOrders);
        stats.put("todayOrders", todayOrders);
        stats.put("totalAmount", totalAmount);
        stats.put("paySuccessRate", Math.round(successRate * 10) / 10.0);
        return stats;
    }

    /**
     * 测试专用：清空订单/支付/链路，并还原库存
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> resetTestData() {
        int clearedTrace = testDataMapper.deleteAllOrderTrace();
        int clearedPay = testDataMapper.deleteAllPayRecord();
        int clearedRefund = testDataMapper.deleteAllRefundRecord();
        int clearedIdempotent = testDataMapper.deleteAllIdempotentRecord();
        int clearedOrder = testDataMapper.deleteAllOrder();
        int restoredProductRows = testDataMapper.resetProductStockToInit();

        Map<String, Object> result = new HashMap<>();
        result.put("clearedOrderCount", clearedOrder);
        result.put("clearedPayCount", clearedPay);
        result.put("clearedTraceCount", clearedTrace);
        result.put("clearedRefundCount", clearedRefund);
        result.put("clearedIdempotentCount", clearedIdempotent);
        result.put("restoredProductRows", restoredProductRows);
        return result;
    }

    private void appendSingleStep(Long orderId,
                                  String service,
                                  String action,
                                  String tech,
                                  String detail,
                                  String status,
                                  long timestamp,
                                  long duration) {
        List<TraceStep> steps = new ArrayList<>(1);
        steps.add(new TraceStep(service, action, tech, detail, status, timestamp, duration));
        appendTrace(orderId, steps);
    }

    private void updateOrderStatusByStateMachine(Long orderId,
                                                 int targetStatus,
                                                 String action,
                                                 String tech,
                                                 String successDetail,
                                                 int... allowedFromStatuses) {
        long stepStart = System.currentTimeMillis();
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            appendSingleStep(orderId, "order-service", action + "失败", tech,
                    "订单不存在，无法更新状态", "warn",
                    System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            return;
        }

        Integer currentStatus = order.getStatus();
        if (currentStatus != null && currentStatus == targetStatus) {
            appendSingleStep(orderId, "order-service", "幂等处理：忽略重复状态更新", "订单状态机幂等更新",
                    String.format("当前状态=%s，与目标状态一致，跳过更新", statusName(currentStatus)),
                    "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            return;
        }

        boolean allowed = Arrays.stream(allowedFromStatuses).anyMatch(status -> status == (currentStatus == null ? -1 : currentStatus));
        if (!allowed) {
            appendSingleStep(orderId, "order-service", "非法状态迁移（拒绝更新）", "订单状态机",
                    String.format("当前状态=%s，目标状态=%s，不允许迁移", statusName(currentStatus), statusName(targetStatus)),
                    "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
            return;
        }

        order.setStatus(targetStatus);
        if (targetStatus == Order.STATUS_PAID) {
            order.setPayTime(LocalDateTime.now());
        }
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
        appendSingleStep(orderId, "order-service", action, tech,
                successDetail, "success",
                System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        log.info("订单状态更新成功, orderId={}, {} -> {}", orderId, statusName(currentStatus), statusName(targetStatus));
    }

    private String statusName(Integer status) {
        if (status == null) {
            return "UNKNOWN(null)";
        }
        return switch (status) {
            case Order.STATUS_PENDING -> "PENDING(待支付)";
            case Order.STATUS_PAYING -> "PAYING(支付中)";
            case Order.STATUS_PAID -> "PAID(已支付)";
            case Order.STATUS_PAY_FAILED -> "PAY_FAILED(支付失败)";
            case Order.STATUS_CLOSED -> "CLOSED(已关闭)";
            case Order.STATUS_REFUNDING -> "REFUNDING(退款中)";
            case Order.STATUS_REFUNDED -> "REFUNDED(已退款)";
            default -> "UNKNOWN(" + status + ")";
        };
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
            log.warn("写入订单调用链失败, orderId={}, error={}", orderId, e.getMessage());
        }
    }
}
