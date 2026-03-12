# P0 支付回调闭环 + 退款流程 实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 补齐 mini-pay 的支付回调、验签幂等、退款申请/回调、前端演示页，形成可完整演示的支付闭环。

**Architecture:** 当前 `createPay` 直接模拟支付成功并同步更新状态。改造后：`createPay` 只创建 PROCESSING 状态的支付单并调 `markPaying`，由外部回调接口驱动支付成功/失败。退款流程同理：申请退款 -> 创建退款单 -> 模拟回调 -> 完成退款。幂等通过 `t_idempotent_record` 唯一索引保证。

**Tech Stack:** Spring Boot 3.4 / Spring Cloud 2024 / MyBatis Plus / RocketMQ / Kafka / Redis / Vue 3 + Element Plus + Vite

---

## 现有代码摘要（实施者必读）

### 已存在的基础设施（不需要新建）
- **DTO 已存在**: `PayCallbackRequest`, `RefundRequest`, `RefundCallbackRequest` (in `mini-pay-common/src/.../dto/`)
- **Entity 已存在**: `PayRecord`(含 outTradeNo/channelTxnNo/callbackTime/callbackRaw), `RefundRecord`, `IdempotentRecord`
- **Mapper 已存在**: `PayRecordMapper`, `RefundRecordMapper`, `IdempotentRecordMapper` (in `mini-pay-pay/.../mapper/`)
- **工具已存在**: `SignUtil`(HmacSHA256 signMap/verifyMap)
- **常量已存在**: `OrderStatus`, `PayStatus`, `RefundStatus`
- **Feign 已存在**: `OrderFeignClient`(含 markPaying/markPayFailed/markRefunding/markRefunded)
- **Gateway 白名单已包含**: `/api/pay/callback`, `/api/pay/refund/callback`
- **Order 状态机已存在**: `OrderService.updateOrderStatusByStateMachine()` 含合法迁移校验 + 幂等跳过 + 链路追踪
- **SQL 表已存在**: `t_pay_record`, `t_refund_record`, `t_idempotent_record`, `t_order_trace`

### 关键文件路径
| 文件 | 路径 |
|------|------|
| PayService | `mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/service/PayService.java` |
| PayController | `mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/controller/PayController.java` |
| OrderService | `mini-pay-backend/mini-pay-order/src/main/java/com/minipay/order/service/OrderService.java` |
| OrderController | `mini-pay-backend/mini-pay-order/src/main/java/com/minipay/order/controller/OrderController.java` |
| TestDataMapper | `mini-pay-backend/mini-pay-order/src/main/java/com/minipay/order/mapper/TestDataMapper.java` |
| Gateway Filter | `mini-pay-backend/mini-pay-gateway/src/main/java/com/minipay/gateway/filter/AuthGlobalFilter.java` |
| pay application.yml | `mini-pay-backend/mini-pay-pay/src/main/resources/application.yml` |
| Frontend API | `mini-pay-web/src/api/index.ts` |
| PayView | `mini-pay-web/src/views/pay/PayView.vue` |
| OrderList | `mini-pay-web/src/views/order/OrderList.vue` |

---

## Task 1: PayService — 改造 createPay 为"待回调"模式

**Files:**
- Modify: `mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/service/PayService.java`

**目标:** `createPay` 不再直接模拟支付成功，而是：生成 `outTradeNo` -> 插入 PROCESSING 支付单 -> Feign 调 `markPaying` -> 返回。删除原来的"模拟第三方支付成功 + MQ 通知 + Kafka 日志"逻辑（这些移到回调中）。

**Step 1: 修改 createPay 方法**

将 `PayService.createPay()` 改为以下逻辑（保留步骤 1~5 的参数校验、Redis 锁、幂等查询、Feign 查订单、写支付记录，但修改步骤 5 的支付记录插入和删除步骤 6~8）：

```java
// ===== 替换步骤 5: 创建支付记录 =====
// 在插入前生成 outTradeNo
String outTradeNo = "PAY" + System.currentTimeMillis() + orderId;
payRecord.setOutTradeNo(outTradeNo);
// status 保持 STATUS_PROCESSING，不再立即置为 SUCCESS
payRecordMapper.insert(payRecord);

// ===== 替换步骤 6~8: 改为调 markPaying =====
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

// 总耗时
result.addStep("pay-service", "支付单创建完成，等待渠道回调", "全链路",
        String.format("outTradeNo=%s, orderId=%s, 支付单已创建（PROCESSING），等待渠道异步回调驱动状态", outTradeNo, orderId),
        "success", System.currentTimeMillis(), System.currentTimeMillis() - start);
```

**Step 2: 同时修改 markPaid 的 Feign 调用**

原 `createPay` 中直接调 `markPaid` 的行为全部删除（包含 RocketMQ 发送和 Feign 降级逻辑），这些逻辑移到 Task 2 的 `handlePayCallback` 中。

**Step 3: 验证编译**

Run: `cd /Users/jizhi/Documents/Yeepay/mini-pay-backend && mvn compile -pl mini-pay-pay -am -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```bash
git add mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/service/PayService.java
git commit -m "refactor(pay): createPay 改为待回调模式，不再同步模拟支付成功"
```

---

## Task 2: PayService — 新增支付回调处理 handlePayCallback

**Files:**
- Modify: `mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/service/PayService.java`

**目标:** 新增 `handlePayCallback(PayCallbackRequest)` 方法，处理验签 -> 幂等 -> 更新支付单 -> 通知订单服务。

**Step 1: 在 PayService 中添加签名密钥常量和新依赖**

```java
// 在类顶部添加
private static final String SIGN_SECRET = "MiniPayCallbackSecret2026";

// 构造函数中添加 IdempotentRecordMapper
private final IdempotentRecordMapper idempotentRecordMapper;
// 更新构造函数参数
```

**Step 2: 实现 handlePayCallback 方法**

```java
/**
 * 处理支付回调（模拟渠道异步通知）
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
    signParams.put("paidAmount", req.getPaidAmount() != null ? req.getPaidAmount().toPlainString() : "");
    signParams.put("callbackTimestamp", req.getCallbackTimestamp());
    signParams.put("nonce", req.getNonce());
    if (!SignUtil.verifyMap(signParams, SIGN_SECRET, req.getSign())) {
        result.addStep("pay-service", "回调验签失败", "HmacSHA256 验签",
                "签名不匹配，拒绝处理。面试考点：防篡改 + 防伪造",
                "fail", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        throw new RuntimeException("回调验签失败");
    }
    result.addStep("pay-service", "回调验签通过", "HmacSHA256 验签",
            String.format("outTradeNo=%s, channelTxnNo=%s", req.getOutTradeNo(), req.getChannelTxnNo()),
            "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

    // 2. 幂等检查（基于 outTradeNo + channelTxnNo）
    stepStart = System.currentTimeMillis();
    String bizKey = req.getOutTradeNo() + ":" + req.getChannelTxnNo();
    IdempotentRecord idempotent = new IdempotentRecord();
    idempotent.setBizType("PAY_CALLBACK");
    idempotent.setBizKey(bizKey);
    idempotent.setStatus(IdempotentRecord.STATUS_PROCESSING);
    idempotent.setCreateTime(LocalDateTime.now());
    try {
        idempotentRecordMapper.insert(idempotent);
    } catch (Exception e) {
        // 唯一索引冲突 = 重复回调
        result.addStep("pay-service", "幂等拦截：重复回调已忽略", "t_idempotent_record 唯一索引",
                String.format("bizKey=%s，已处理过，跳过。面试考点：数据库唯一索引保证幂等", bizKey),
                "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        // 返回已有的支付记录
        PayRecord existing = payRecordMapper.selectOne(
                new LambdaQueryWrapper<PayRecord>().eq(PayRecord::getOutTradeNo, req.getOutTradeNo()));
        result.setData(existing);
        appendTrace(existing != null ? existing.getOrderId() : null, result.getTrace());
        return result;
    }
    result.addStep("pay-service", "幂等记录写入成功", "t_idempotent_record",
            String.format("bizType=PAY_CALLBACK, bizKey=%s", bizKey),
            "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

    // 3. 查询支付单
    stepStart = System.currentTimeMillis();
    PayRecord payRecord = payRecordMapper.selectOne(
            new LambdaQueryWrapper<PayRecord>().eq(PayRecord::getOutTradeNo, req.getOutTradeNo()));
    if (payRecord == null) {
        result.addStep("pay-service", "支付单不存在", "MySQL 查询",
                String.format("outTradeNo=%s 无匹配记录", req.getOutTradeNo()),
                "fail", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        throw new RuntimeException("支付单不存在: " + req.getOutTradeNo());
    }
    result.addStep("pay-service", "查询到支付单", "MyBatis Plus",
            String.format("payId=%s, orderId=%s, currentStatus=%s", payRecord.getId(), payRecord.getOrderId(), payRecord.getStatus()),
            "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

    // 4. 如果支付单已终态，跳过
    if (payRecord.getStatus() == PayRecord.STATUS_SUCCESS || payRecord.getStatus() == PayRecord.STATUS_FAIL) {
        result.addStep("pay-service", "支付单已终态，跳过更新", "幂等保护",
                String.format("当前状态=%s", payRecord.getStatus()), "warn",
                System.currentTimeMillis(), 0);
        result.setData(payRecord);
        appendTrace(payRecord.getOrderId(), result.getTrace());
        return result;
    }

    Long orderId = payRecord.getOrderId();

    // 5. 根据回调结果处理
    boolean isSuccess = "SUCCESS".equalsIgnoreCase(req.getPayStatus());
    stepStart = System.currentTimeMillis();

    payRecord.setChannelTxnNo(req.getChannelTxnNo());
    payRecord.setCallbackTime(LocalDateTime.now());
    payRecord.setCallbackRaw(req.getRawBody());
    payRecord.setUpdateTime(LocalDateTime.now());

    if (isSuccess) {
        payRecord.setStatus(PayRecord.STATUS_SUCCESS);
        payRecord.setFinishTime(LocalDateTime.now());
        payRecordMapper.updateById(payRecord);
        result.addStep("pay-service", "更新支付单为成功", "MySQL",
                String.format("payId=%s, channelTxnNo=%s", payRecord.getId(), req.getChannelTxnNo()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // Kafka 日志
        stepStart = System.currentTimeMillis();
        try {
            String payLogMessage = buildPayLogMessage(payRecord);
            kafkaTemplate.send("pay-log-topic", orderId.toString(), payLogMessage)
                    .get(2, TimeUnit.SECONDS);
            result.addStep("pay-service → Kafka → notification-service", "发送支付流水日志", "Kafka",
                    String.format("topic=pay-log-topic, key=%s", orderId),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        } catch (Exception e) {
            log.warn("Kafka 日志发送失败, orderId={}", orderId);
            result.addStep("pay-service → Kafka", "发送支付流水日志失败", "Kafka",
                    "日志写入失败不影响主链路", "warn",
                    System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        }

        // RocketMQ 通知 + Feign 降级
        stepStart = System.currentTimeMillis();
        try {
            rocketMQTemplate.convertAndSend("pay-result-topic", orderId.toString());
            result.addStep("pay-service → RocketMQ → order-service", "RocketMQ 异步通知订单服务", "RocketMQ",
                    String.format("topic=pay-result-topic, orderId=%s", orderId),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        } catch (Exception e) {
            long mqDuration = System.currentTimeMillis() - stepStart;
            result.addStep("pay-service → RocketMQ", "RocketMQ 发送失败，降级 Feign", "RocketMQ",
                    e.getMessage(), "warn", System.currentTimeMillis(), mqDuration);
            stepStart = System.currentTimeMillis();
            orderFeignClient.markPaid(orderId, req.getChannelTxnNo());
            result.addStep("pay-service → order-service", "Feign 降级调用 markPaid", "OpenFeign（降级方案）",
                    String.format("orderId=%s", orderId),
                    "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        }
    } else {
        payRecord.setStatus(PayRecord.STATUS_FAIL);
        payRecord.setFinishTime(LocalDateTime.now());
        payRecordMapper.updateById(payRecord);
        result.addStep("pay-service", "更新支付单为失败", "MySQL",
                String.format("payId=%s, reason=%s", payRecord.getId(), req.getFailReason()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 通知订单失败
        stepStart = System.currentTimeMillis();
        orderFeignClient.markPayFailed(orderId, req.getFailReason());
        result.addStep("pay-service → order-service", "Feign 通知订单支付失败", "OpenFeign + 订单状态机",
                String.format("orderId=%s, reason=%s", orderId, req.getFailReason()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
    }

    // 更新幂等记录为成功
    idempotent.setStatus(IdempotentRecord.STATUS_SUCCESS);
    idempotent.setUpdateTime(LocalDateTime.now());
    idempotentRecordMapper.updateById(idempotent);

    result.addStep("pay-service", "支付回调处理完成", "全链路",
            String.format("outTradeNo=%s, result=%s", req.getOutTradeNo(), isSuccess ? "SUCCESS" : "FAIL"),
            "success", System.currentTimeMillis(), System.currentTimeMillis() - start);

    result.setData(payRecord);
    appendTrace(orderId, result.getTrace());
    return result;
}
```

**Step 3: 添加必要的 import**

```java
import com.minipay.common.dto.PayCallbackRequest;
import com.minipay.common.entity.IdempotentRecord;
import com.minipay.common.util.SignUtil;
import com.minipay.pay.mapper.IdempotentRecordMapper;
import java.util.Map;
import java.util.TreeMap;
```

**Step 4: 验证编译**

Run: `cd /Users/jizhi/Documents/Yeepay/mini-pay-backend && mvn compile -pl mini-pay-pay -am -q`
Expected: BUILD SUCCESS

**Step 5: Commit**

```bash
git add mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/service/PayService.java
git commit -m "feat(pay): 新增支付回调处理，含验签、幂等、MQ通知与Feign降级"
```

---

## Task 3: PayService — 新增退款申请 applyRefund

**Files:**
- Modify: `mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/service/PayService.java`

**目标:** 新增 `applyRefund(Long userId, RefundRequest req)` 方法，创建退款记录并调用 `markRefunding`。

**Step 1: 实现 applyRefund 方法**

```java
/**
 * 退款申请
 */
public TraceResult<RefundRecord> applyRefund(Long userId, RefundRequest req) {
    TraceResult<RefundRecord> result = new TraceResult<>();
    long start = System.currentTimeMillis();
    long stepStart;

    // 1. 查询支付记录
    stepStart = System.currentTimeMillis();
    PayRecord payRecord = payRecordMapper.selectOne(
            new LambdaQueryWrapper<PayRecord>()
                    .eq(PayRecord::getOrderId, req.getOrderId())
                    .eq(PayRecord::getStatus, PayRecord.STATUS_SUCCESS));
    if (payRecord == null) {
        throw new RuntimeException("无成功的支付记录，无法退款");
    }
    result.addStep("pay-service", "查询支付记录", "MyBatis Plus",
            String.format("payId=%s, orderId=%s, paidAmount=%s", payRecord.getId(), req.getOrderId(), payRecord.getAmount()),
            "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

    // 2. 校验退款金额
    stepStart = System.currentTimeMillis();
    BigDecimal refundAmount = req.getRefundAmount();
    if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
        throw new RuntimeException("退款金额必须大于0");
    }
    if (refundAmount.compareTo(payRecord.getAmount()) > 0) {
        throw new RuntimeException("退款金额不能超过支付金额");
    }
    result.addStep("pay-service", "退款金额校验通过", "业务校验",
            String.format("refundAmount=%s, paidAmount=%s", refundAmount, payRecord.getAmount()),
            "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

    // 3. 创建退款记录
    stepStart = System.currentTimeMillis();
    String refundNo = "REF" + System.currentTimeMillis() + req.getOrderId();
    RefundRecord refund = new RefundRecord();
    refund.setOrderId(req.getOrderId());
    refund.setPayId(payRecord.getId());
    refund.setUserId(userId);
    refund.setRefundNo(refundNo);
    refund.setAmount(refundAmount);
    refund.setReason(req.getReason() != null ? req.getReason() : "用户申请退款");
    refund.setStatus(RefundRecord.STATUS_PROCESSING);
    refund.setCreateTime(LocalDateTime.now());
    refundRecordMapper.insert(refund);
    result.addStep("pay-service", "创建退款记录", "MyBatis Plus",
            String.format("refundNo=%s, amount=%s, reason=%s", refundNo, refundAmount, refund.getReason()),
            "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

    // 4. 通知订单服务进入退款中
    stepStart = System.currentTimeMillis();
    try {
        orderFeignClient.markRefunding(req.getOrderId(), "退款申请已受理，refundNo=" + refundNo);
        result.addStep("pay-service → order-service", "Feign 通知订单进入退款中", "OpenFeign + 订单状态机",
                String.format("orderId=%s, PAID → REFUNDING", req.getOrderId()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
    } catch (Exception e) {
        log.warn("通知订单退款中失败, orderId={}", req.getOrderId());
        result.addStep("pay-service → order-service", "通知订单退款中失败", "OpenFeign",
                e.getMessage(), "warn", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
    }

    result.addStep("pay-service", "退款申请完成，等待渠道回调", "全链路",
            String.format("refundNo=%s, orderId=%s", refundNo, req.getOrderId()),
            "success", System.currentTimeMillis(), System.currentTimeMillis() - start);

    result.setData(refund);
    appendTrace(req.getOrderId(), result.getTrace());
    return result;
}
```

**Step 2: 添加 import**

```java
import com.minipay.common.dto.RefundRequest;
import com.minipay.common.entity.RefundRecord;
import com.minipay.pay.mapper.RefundRecordMapper;
import java.math.BigDecimal;
```

确保构造函数注入 `RefundRecordMapper refundRecordMapper`。

**Step 3: 验证编译**

Run: `cd /Users/jizhi/Documents/Yeepay/mini-pay-backend && mvn compile -pl mini-pay-pay -am -q`

**Step 4: Commit**

```bash
git add mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/service/PayService.java
git commit -m "feat(pay): 新增退款申请，创建退款单并通知订单服务"
```

---

## Task 4: PayService — 新增退款回调 handleRefundCallback

**Files:**
- Modify: `mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/service/PayService.java`

**目标:** 新增 `handleRefundCallback(RefundCallbackRequest)` 方法，处理验签 -> 幂等 -> 更新退款单 -> 通知订单服务。

**Step 1: 实现 handleRefundCallback 方法**

```java
/**
 * 处理退款回调
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
    signParams.put("refundAmount", req.getRefundAmount() != null ? req.getRefundAmount().toPlainString() : "");
    signParams.put("callbackTimestamp", req.getCallbackTimestamp());
    signParams.put("nonce", req.getNonce());
    if (!SignUtil.verifyMap(signParams, SIGN_SECRET, req.getSign())) {
        result.addStep("pay-service", "退款回调验签失败", "HmacSHA256 验签",
                "签名不匹配", "fail", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        throw new RuntimeException("退款回调验签失败");
    }
    result.addStep("pay-service", "退款回调验签通过", "HmacSHA256 验签",
            String.format("refundNo=%s", req.getRefundNo()),
            "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

    // 2. 幂等
    stepStart = System.currentTimeMillis();
    String bizKey = req.getRefundNo() + ":" + req.getChannelRefundNo();
    IdempotentRecord idempotent = new IdempotentRecord();
    idempotent.setBizType("REFUND_CALLBACK");
    idempotent.setBizKey(bizKey);
    idempotent.setStatus(IdempotentRecord.STATUS_PROCESSING);
    idempotent.setCreateTime(LocalDateTime.now());
    try {
        idempotentRecordMapper.insert(idempotent);
    } catch (Exception e) {
        result.addStep("pay-service", "幂等拦截：重复退款回调已忽略", "t_idempotent_record 唯一索引",
                String.format("bizKey=%s", bizKey), "warn",
                System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
        RefundRecord existing = refundRecordMapper.selectOne(
                new LambdaQueryWrapper<RefundRecord>().eq(RefundRecord::getRefundNo, req.getRefundNo()));
        result.setData(existing);
        appendTrace(existing != null ? existing.getOrderId() : null, result.getTrace());
        return result;
    }
    result.addStep("pay-service", "幂等记录写入成功", "t_idempotent_record",
            String.format("bizType=REFUND_CALLBACK, bizKey=%s", bizKey),
            "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

    // 3. 查退款单
    stepStart = System.currentTimeMillis();
    RefundRecord refund = refundRecordMapper.selectOne(
            new LambdaQueryWrapper<RefundRecord>().eq(RefundRecord::getRefundNo, req.getRefundNo()));
    if (refund == null) {
        throw new RuntimeException("退款单不存在: " + req.getRefundNo());
    }
    result.addStep("pay-service", "查询到退款单", "MyBatis Plus",
            String.format("refundId=%s, orderId=%s", refund.getId(), refund.getOrderId()),
            "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

    // 4. 如果已终态，跳过
    if (refund.getStatus() == RefundRecord.STATUS_SUCCESS || refund.getStatus() == RefundRecord.STATUS_FAIL) {
        result.addStep("pay-service", "退款单已终态，跳过", "幂等保护",
                String.format("当前状态=%s", refund.getStatus()), "warn", System.currentTimeMillis(), 0);
        result.setData(refund);
        appendTrace(refund.getOrderId(), result.getTrace());
        return result;
    }

    Long orderId = refund.getOrderId();
    boolean isSuccess = "SUCCESS".equalsIgnoreCase(req.getRefundStatus());
    stepStart = System.currentTimeMillis();

    refund.setChannelRefundNo(req.getChannelRefundNo());
    refund.setCallbackTime(LocalDateTime.now());
    refund.setCallbackRaw(req.getRawBody());
    refund.setUpdateTime(LocalDateTime.now());

    if (isSuccess) {
        refund.setStatus(RefundRecord.STATUS_SUCCESS);
        refund.setFinishTime(LocalDateTime.now());
        refundRecordMapper.updateById(refund);
        result.addStep("pay-service", "更新退款单为成功", "MySQL",
                String.format("refundNo=%s, channelRefundNo=%s", req.getRefundNo(), req.getChannelRefundNo()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);

        // 通知订单已退款
        stepStart = System.currentTimeMillis();
        orderFeignClient.markRefunded(orderId, req.getRefundNo());
        result.addStep("pay-service → order-service", "Feign 通知订单已退款（含库存回滚）", "OpenFeign + 订单状态机",
                String.format("orderId=%s, refundNo=%s, REFUNDING → REFUNDED", orderId, req.getRefundNo()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
    } else {
        refund.setStatus(RefundRecord.STATUS_FAIL);
        refund.setFinishTime(LocalDateTime.now());
        refundRecordMapper.updateById(refund);
        result.addStep("pay-service", "更新退款单为失败", "MySQL",
                String.format("refundNo=%s, reason=%s", req.getRefundNo(), req.getFailReason()),
                "success", System.currentTimeMillis(), System.currentTimeMillis() - stepStart);
    }

    // 更新幂等记录
    idempotent.setStatus(IdempotentRecord.STATUS_SUCCESS);
    idempotent.setUpdateTime(LocalDateTime.now());
    idempotentRecordMapper.updateById(idempotent);

    result.addStep("pay-service", "退款回调处理完成", "全链路",
            String.format("refundNo=%s, result=%s", req.getRefundNo(), isSuccess ? "SUCCESS" : "FAIL"),
            "success", System.currentTimeMillis(), System.currentTimeMillis() - start);

    result.setData(refund);
    appendTrace(orderId, result.getTrace());
    return result;
}
```

**Step 2: 添加 import**

```java
import com.minipay.common.dto.RefundCallbackRequest;
```

**Step 3: 新增按订单查询退款记录方法（供前端展示）**

```java
/**
 * 按订单查退款记录
 */
public List<RefundRecord> listRefundsByOrderId(Long orderId) {
    return refundRecordMapper.selectList(
            new LambdaQueryWrapper<RefundRecord>()
                    .eq(RefundRecord::getOrderId, orderId)
                    .orderByDesc(RefundRecord::getCreateTime));
}
```

**Step 4: 验证编译**

Run: `cd /Users/jizhi/Documents/Yeepay/mini-pay-backend && mvn compile -pl mini-pay-pay -am -q`

**Step 5: Commit**

```bash
git add mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/service/PayService.java
git commit -m "feat(pay): 新增退款回调处理与退款记录查询"
```

---

## Task 5: PayController — 暴露回调与退款接口

**Files:**
- Modify: `mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/controller/PayController.java`

**目标:** 新增 4 个接口端点。

**Step 1: 添加接口**

```java
import com.minipay.common.dto.PayCallbackRequest;
import com.minipay.common.dto.RefundRequest;
import com.minipay.common.dto.RefundCallbackRequest;
import com.minipay.common.entity.RefundRecord;
import java.util.List;

// --- 在 PayController 类中追加 ---

/**
 * 模拟支付渠道回调（无需 JWT，已在 Gateway 白名单）
 */
@PostMapping("/callback/mock")
public R<TraceResult<PayRecord>> payCallback(@RequestBody PayCallbackRequest request) {
    return R.ok(payService.handlePayCallback(request));
}

/**
 * 退款申请
 */
@PostMapping("/refund")
public R<TraceResult<RefundRecord>> refund(@RequestBody RefundRequest request,
                                           @RequestHeader("X-User-Id") Long userId) {
    return R.ok(payService.applyRefund(userId, request));
}

/**
 * 模拟退款渠道回调（无需 JWT，已在 Gateway 白名单）
 */
@PostMapping("/refund/callback/mock")
public R<TraceResult<RefundRecord>> refundCallback(@RequestBody RefundCallbackRequest request) {
    return R.ok(payService.handleRefundCallback(request));
}

/**
 * 查询订单的退款记录
 */
@GetMapping("/refund/list/{orderId}")
public R<List<RefundRecord>> refundList(@PathVariable Long orderId) {
    return R.ok(payService.listRefundsByOrderId(orderId));
}
```

**Step 2: 验证编译**

Run: `cd /Users/jizhi/Documents/Yeepay/mini-pay-backend && mvn compile -pl mini-pay-pay -am -q`

**Step 3: Commit**

```bash
git add mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/controller/PayController.java
git commit -m "feat(pay): 暴露支付回调、退款申请、退款回调、退款查询接口"
```

---

## Task 6: TestDataMapper — 补全退款/幂等表清理

**Files:**
- Modify: `mini-pay-backend/mini-pay-order/src/main/java/com/minipay/order/mapper/TestDataMapper.java`
- Modify: `mini-pay-backend/mini-pay-order/src/main/java/com/minipay/order/service/OrderService.java`

**目标:** 重置测试数据时同时清理退款记录和幂等记录。

**Step 1: 在 TestDataMapper 中追加两个方法**

```java
@Delete("DELETE FROM t_refund_record")
int deleteAllRefundRecord();

@Delete("DELETE FROM t_idempotent_record")
int deleteAllIdempotentRecord();
```

**Step 2: 在 OrderService.resetTestData() 中追加调用**

在 `resetTestData()` 方法的 `clearedPay` 行后追加：

```java
int clearedRefund = testDataMapper.deleteAllRefundRecord();
int clearedIdempotent = testDataMapper.deleteAllIdempotentRecord();
```

并在 result map 中追加：

```java
result.put("clearedRefundCount", clearedRefund);
result.put("clearedIdempotentCount", clearedIdempotent);
```

**Step 3: 验证编译**

Run: `cd /Users/jizhi/Documents/Yeepay/mini-pay-backend && mvn compile -pl mini-pay-order -am -q`

**Step 4: Commit**

```bash
git add mini-pay-backend/mini-pay-order/src/main/java/com/minipay/order/mapper/TestDataMapper.java \
       mini-pay-backend/mini-pay-order/src/main/java/com/minipay/order/service/OrderService.java
git commit -m "feat(order): 测试数据重置补全退款记录和幂等记录清理"
```

---

## Task 7: OrderList.vue — 补全状态显示 + 退款按钮

**Files:**
- Modify: `mini-pay-web/src/views/order/OrderList.vue`

**目标:** statusMap 补全 PAYING(4), PAY_FAILED(5), REFUNDING(6)；已支付订单显示"退款"按钮。

**Step 1: 更新 statusMap**

```typescript
const statusMap: Record<number, { text: string; key: string }> = {
  0: { text: '待支付', key: 'pending' },
  1: { text: '已支付', key: 'paid' },
  2: { text: '已关闭', key: 'closed' },
  3: { text: '已退款', key: 'refunded' },
  4: { text: '支付中', key: 'paying' },
  5: { text: '支付失败', key: 'payFailed' },
  6: { text: '退款中', key: 'refunding' },
}
```

**Step 2: 更新 statusType 函数**

```typescript
function statusType(key: string) {
  const map: Record<string, string> = {
    pending: 'warning',
    paying: '',
    paid: 'success',
    closed: 'info',
    refunded: 'danger',
    payFailed: 'danger',
    refunding: 'warning',
  }
  return map[key] || 'info'
}
```

**Step 3: 更新操作列模板**

替换操作列 `<template #default="{ row }">` 为：

```html
<template #default="{ row }">
  <el-button v-if="row.statusKey === 'pending'" type="primary" size="small" @click="handlePay(row.id)">
    去支付
  </el-button>
  <el-button v-if="row.statusKey === 'paying'" size="small" @click="handleMockCallback(row.id, 'success')">
    模拟回调成功
  </el-button>
  <el-button v-if="row.statusKey === 'paying'" size="small" type="danger" @click="handleMockCallback(row.id, 'fail')">
    模拟回调失败
  </el-button>
  <el-button v-if="row.statusKey === 'paid'" type="warning" size="small" @click="handleRefund(row)">
    退款
  </el-button>
  <el-button v-if="row.statusKey === 'refunding'" size="small" @click="handleMockRefundCallback(row.id, 'success')">
    模拟退款成功
  </el-button>
  <el-button v-if="row.statusKey === 'refunding'" size="small" type="danger" @click="handleMockRefundCallback(row.id, 'fail')">
    模拟退款失败
  </el-button>
  <el-button size="small" @click="handleViewTrace(row.id)">查看链路</el-button>
</template>
```

**Step 4: 添加退款相关的方法和弹窗逻辑**

在 `<script setup>` 中导入新 API 并添加方法（参见 Task 9 的前端 API 定义）：

```typescript
import { orderApi, payApi } from '../../api'

// --- 退款弹窗 ---
const refundDialogVisible = ref(false)
const refundingOrder = ref<any>(null)
const refundAmount = ref(0)
const refundReason = ref('用户申请退款')

function handleRefund(order: any) {
  refundingOrder.value = order
  refundAmount.value = order.amount
  refundReason.value = '用户申请退款'
  refundDialogVisible.value = true
}

async function confirmRefund() {
  try {
    await payApi.refund({
      orderId: refundingOrder.value.id,
      refundAmount: refundAmount.value,
      reason: refundReason.value,
    })
    ElMessage.success('退款申请已提交')
    refundDialogVisible.value = false
    // 刷新列表
    const res: any = await orderApi.list()
    orders.value = (res.data || []).map((o: any) => ({
      ...o,
      statusKey: statusMap[o.status]?.key || 'info',
      statusText: statusMap[o.status]?.text || '未知',
    }))
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '退款失败')
  }
}

// --- 模拟支付回调 ---
async function handleMockCallback(orderId: string, type: 'success' | 'fail') {
  try {
    await payApi.mockPayCallback(orderId, type)
    ElMessage.success(type === 'success' ? '模拟支付成功回调已发送' : '模拟支付失败回调已发送')
    // 刷新
    const res: any = await orderApi.list()
    orders.value = (res.data || []).map((o: any) => ({
      ...o,
      statusKey: statusMap[o.status]?.key || 'info',
      statusText: statusMap[o.status]?.text || '未知',
    }))
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '回调失败')
  }
}

// --- 模拟退款回调 ---
async function handleMockRefundCallback(orderId: string, type: 'success' | 'fail') {
  try {
    await payApi.mockRefundCallback(orderId, type)
    ElMessage.success(type === 'success' ? '模拟退款成功回调已发送' : '模拟退款失败回调已发送')
    const res: any = await orderApi.list()
    orders.value = (res.data || []).map((o: any) => ({
      ...o,
      statusKey: statusMap[o.status]?.key || 'info',
      statusText: statusMap[o.status]?.text || '未知',
    }))
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '回调失败')
  }
}
```

**Step 5: 在 template 中追加退款弹窗**

在 `</el-dialog>` 后追加：

```html
<!-- 退款弹窗 -->
<el-dialog v-model="refundDialogVisible" title="退款申请" width="500px">
  <el-form label-width="80px">
    <el-form-item label="订单号">{{ refundingOrder?.id }}</el-form-item>
    <el-form-item label="商品">{{ refundingOrder?.productName }}</el-form-item>
    <el-form-item label="退款金额">
      <el-input-number v-model="refundAmount" :min="0.01" :max="refundingOrder?.amount || 0" :precision="2" />
    </el-form-item>
    <el-form-item label="退款原因">
      <el-input v-model="refundReason" type="textarea" :rows="2" />
    </el-form-item>
  </el-form>
  <template #footer>
    <el-button @click="refundDialogVisible = false">取消</el-button>
    <el-button type="primary" @click="confirmRefund">确认退款</el-button>
  </template>
</el-dialog>
```

**Step 6: Commit**

```bash
git add mini-pay-web/src/views/order/OrderList.vue
git commit -m "feat(web): 订单列表补全所有状态显示，新增退款与模拟回调按钮"
```

---

## Task 8: PayView.vue — 支付页支持待回调模式

**Files:**
- Modify: `mini-pay-web/src/views/pay/PayView.vue`

**目标:** 支付创建后不再显示"支付成功"，而是显示"等待回调"状态，附带"模拟成功回调"和"模拟失败回调"按钮。

**Step 1: 修改 payStatus 类型和 handlePay 逻辑**

```typescript
const payStatus = ref<'pending' | 'waiting' | 'success' | 'fail'>('pending')
const currentPayRecord = ref<any>(null)

async function handlePay() {
  paying.value = true
  try {
    const res: any = await payApi.create(orderId, payMethod.value)
    const traceData = res.data
    if (traceData && traceData.trace && traceData.trace.length > 0) {
      payTrace.value = traceData.trace
    }
    if (traceData && traceData.data) {
      currentPayRecord.value = traceData.data
    }
    payStatus.value = 'waiting'
    ElMessage.info('支付单已创建，等待渠道回调')
  } catch {
    payStatus.value = 'fail'
    ElMessage.error('支付单创建失败')
  } finally {
    paying.value = false
  }
}
```

**Step 2: 添加模拟回调方法**

```typescript
const callbacking = ref(false)

async function handleMockCallback(type: 'success' | 'fail') {
  callbacking.value = true
  try {
    const res: any = await payApi.mockPayCallback(orderId, type)
    const traceData = res.data
    if (traceData && traceData.trace && traceData.trace.length > 0) {
      payTrace.value = [...payTrace.value, ...traceData.trace]
    }
    payStatus.value = type === 'success' ? 'success' : 'fail'
    ElMessage[type === 'success' ? 'success' : 'error'](
      type === 'success' ? '支付回调成功！' : '支付回调失败'
    )
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '回调处理失败')
  } finally {
    callbacking.value = false
  }
}
```

**Step 3: 更新 template**

在 `<!-- 待支付 -->` card 后、`<!-- 支付成功 -->` 前，插入"等待回调"状态：

```html
<!-- 等待回调 -->
<template v-else-if="payStatus === 'waiting'">
  <el-result icon="info" title="支付单已创建" sub-title="正在等待渠道回调，请点击下方按钮模拟回调结果">
    <template #extra>
      <el-button type="success" :loading="callbacking" @click="handleMockCallback('success')">
        模拟回调成功
      </el-button>
      <el-button type="danger" :loading="callbacking" @click="handleMockCallback('fail')">
        模拟回调失败
      </el-button>
    </template>
  </el-result>
  <TraceTimeline v-if="payTrace.length > 0" title="支付创建调用链路" :steps="payTrace" />
</template>
```

**Step 4: Commit**

```bash
git add mini-pay-web/src/views/pay/PayView.vue
git commit -m "feat(web): 支付页改为待回调模式，支持模拟成功/失败回调"
```

---

## Task 9: API 层 — 前端新增 API 方法

**Files:**
- Modify: `mini-pay-web/src/api/index.ts`

**目标:** 新增 payApi 的回调/退款相关方法。模拟回调时前端负责生成签名参数（使用与后端一致的 HmacSHA256 密钥），让演示闭环。

**Step 1: 安装 crypto-js**

Run: `cd /Users/jizhi/Documents/Yeepay/mini-pay-web && npm install crypto-js && npm install -D @types/crypto-js`

**Step 2: 新建签名工具文件**

Create: `mini-pay-web/src/utils/sign.ts`

```typescript
import CryptoJS from 'crypto-js'

const SIGN_SECRET = 'MiniPayCallbackSecret2026'

export function signParams(params: Record<string, string | number | undefined>): string {
  const sorted = Object.keys(params)
    .filter(k => k !== 'sign' && params[k] !== undefined && params[k] !== null)
    .sort()
  const canonical = sorted.map(k => `${k}=${params[k]}`).join('&')
  return CryptoJS.HmacSHA256(canonical, SIGN_SECRET).toString(CryptoJS.enc.Hex)
}
```

**Step 3: 更新 api/index.ts 的 payApi**

```typescript
import { signParams } from '../utils/sign'

export const payApi = {
  create: (orderId: string, channel: string = 'alipay') =>
    request.post('/pay/create', { orderId, channel }),
  query: (payId: string) => request.get(`/pay/query/${payId}`),

  // 模拟支付回调（前端构造带签名的回调请求）
  mockPayCallback: async (orderId: string, type: 'success' | 'fail') => {
    // 先查询支付记录获取 outTradeNo
    const orderRes: any = await request.get(`/pay/query-by-order/${orderId}`)
    const payRecord = orderRes.data
    const outTradeNo = payRecord?.outTradeNo || ''

    const nonce = Math.random().toString(36).substring(2, 15)
    const callbackTimestamp = Date.now()
    const channelTxnNo = 'CH' + callbackTimestamp + Math.floor(Math.random() * 10000)
    const payStatus = type === 'success' ? 'SUCCESS' : 'FAIL'
    const paidAmount = payRecord?.amount || '0'

    const params: Record<string, any> = {
      outTradeNo,
      channelTxnNo,
      payStatus,
      paidAmount: String(paidAmount),
      callbackTimestamp,
      nonce,
    }
    const sign = signParams(params)

    return request.post('/pay/callback/mock', {
      outTradeNo,
      channelTxnNo,
      payStatus,
      paidAmount,
      failReason: type === 'fail' ? '渠道返回支付失败' : null,
      callbackTimestamp,
      nonce,
      rawBody: JSON.stringify(params),
      sign,
    })
  },

  // 退款申请
  refund: (data: { orderId: string; refundAmount: number; reason: string }) =>
    request.post('/pay/refund', data),

  // 查询订单的退款记录
  refundList: (orderId: string) => request.get(`/pay/refund/list/${orderId}`),

  // 模拟退款回调
  mockRefundCallback: async (orderId: string, type: 'success' | 'fail') => {
    // 查退款记录
    const refundRes: any = await request.get(`/pay/refund/list/${orderId}`)
    const refunds = refundRes.data || []
    const refund = refunds.find((r: any) => r.status === 0) || refunds[0]
    if (!refund) throw new Error('无退款记录')

    const nonce = Math.random().toString(36).substring(2, 15)
    const callbackTimestamp = Date.now()
    const channelRefundNo = 'CHREF' + callbackTimestamp + Math.floor(Math.random() * 10000)
    const refundStatus = type === 'success' ? 'SUCCESS' : 'FAIL'

    const params: Record<string, any> = {
      refundNo: refund.refundNo,
      channelRefundNo,
      refundStatus,
      refundAmount: String(refund.amount),
      callbackTimestamp,
      nonce,
    }
    const sign = signParams(params)

    return request.post('/pay/refund/callback/mock', {
      refundNo: refund.refundNo,
      channelRefundNo,
      refundStatus,
      refundAmount: refund.amount,
      failReason: type === 'fail' ? '渠道返回退款失败' : null,
      callbackTimestamp,
      nonce,
      rawBody: JSON.stringify(params),
      sign,
    })
  },
}
```

**Step 4: PayController 补一个按订单查支付记录的接口**

在 `PayController.java` 中追加：

```java
@GetMapping("/query-by-order/{orderId}")
public R<PayRecord> queryByOrder(@PathVariable Long orderId) {
    return R.ok(payService.queryByOrderId(orderId));
}
```

在 `PayService.java` 中追加：

```java
public PayRecord queryByOrderId(Long orderId) {
    return payRecordMapper.selectOne(
            new LambdaQueryWrapper<PayRecord>()
                    .eq(PayRecord::getOrderId, orderId)
                    .orderByDesc(PayRecord::getCreateTime)
                    .last("LIMIT 1"));
}
```

**Step 5: Commit**

```bash
git add mini-pay-web/src/api/index.ts mini-pay-web/src/utils/sign.ts \
       mini-pay-web/package.json mini-pay-web/package-lock.json \
       mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/controller/PayController.java \
       mini-pay-backend/mini-pay-pay/src/main/java/com/minipay/pay/service/PayService.java
git commit -m "feat(web+pay): 前端API层新增回调/退款方法，含签名生成；后端补按订单查支付记录接口"
```

---

## Task 10: 后端 Maven 构建验证

**Step 1: 全量编译**

Run: `cd /Users/jizhi/Documents/Yeepay/mini-pay-backend && mvn clean compile -q`
Expected: BUILD SUCCESS（如有编译错误，逐个修复后重新编译）

**Step 2: 常见编译问题检查清单**

- `PayService` 构造函数需包含 `IdempotentRecordMapper` 和 `RefundRecordMapper` 两个新注入
- 确保 `markPaid` 的 Feign 调用签名匹配（两个参数版本）
- 确保所有 import 正确

**Step 3: Commit（如有修复）**

```bash
git add -A
git commit -m "fix: 修正编译问题"
```

---

## Task 11: 前端 Vite 构建验证

**Step 1: 安装依赖并构建**

Run: `cd /Users/jizhi/Documents/Yeepay/mini-pay-web && npm install && npm run build`
Expected: build 成功无报错

**Step 2: 常见构建问题检查清单**

- `crypto-js` 和 `@types/crypto-js` 已安装
- `payApi.create` 调用签名已更新（多了 `channel` 参数）
- `sign.ts` 文件路径正确

**Step 3: Commit（如有修复）**

```bash
git add -A
git commit -m "fix: 修正前端构建问题"
```

---

## 完成定义（DoD）

1. **支付创建** → 返回 PROCESSING 支付单 + outTradeNo，订单状态 PAYING
2. **模拟支付成功回调** → 验签通过 → 幂等通过 → 支付单 SUCCESS → 订单 PAID
3. **模拟支付失败回调** → 验签通过 → 支付单 FAIL → 订单 PAY_FAILED
4. **重复回调** → 幂等拦截，状态不变
5. **退款申请** → 退款单 PROCESSING → 订单 REFUNDING
6. **模拟退款成功回调** → 退款单 SUCCESS → 订单 REFUNDED + 库存回滚
7. **前端完整演示链路**: 下单 → 去支付 → 模拟回调成功 → 退款 → 模拟退款成功
8. **后端 `mvn clean compile` 通过**
9. **前端 `npm run build` 通过**

---

## 任务依赖图

```
Task 1 (改造createPay)
    ↓
Task 2 (支付回调) ──→ Task 5 (Controller) ──→ Task 10 (Maven构建)
    ↓
Task 3 (退款申请) ──→ Task 5
    ↓
Task 4 (退款回调) ──→ Task 5
                            ↓
Task 6 (TestDataMapper) ────→ Task 10
                            ↓
Task 7 (OrderList.vue) ──→ Task 9 (API层) ──→ Task 11 (Vite构建)
    ↓
Task 8 (PayView.vue) ────→ Task 9
```
