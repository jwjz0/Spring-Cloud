# Mini Pay Backend - CLAUDE.md

## 项目概述

Spring Cloud 微服务支付系统后端，用于学习微服务架构、消息队列和分布式事务。

## 技术栈

- Java 21 + Spring Boot 3.4.3 + Spring Cloud 2024.0.1
- Spring Cloud Alibaba 2023.0.3.2 (Nacos 服务注册/发现)
- MyBatis Plus 3.5.9 (ORM)
- MySQL 8.0 (数据库: `mini_pay`, 用户: root/416416)
- Redis (分布式锁、缓存)
- RocketMQ (延迟消息、支付结果通知)
- Kafka (支付日志流)
- JWT (jjwt 0.12.6, 鉴权)

## 模块与端口

| 模块 | 端口 | 职责 |
|------|------|------|
| mini-pay-gateway | 8080 | API 网关, JWT 鉴权, 路由转发 |
| mini-pay-user | 8081 | 注册/登录, BCrypt 密码 |
| mini-pay-product | 8082 | 商品查询, 库存扣减(乐观锁) |
| mini-pay-order | 8083 | 下单, 订单状态机, RocketMQ 延迟关单 |
| mini-pay-pay | 8084 | 支付/退款, Redis 幂等锁, Kafka 日志 |
| mini-pay-notification | 8085 | Kafka 消费支付日志 |
| mini-pay-common | - | 公共 DTO/Entity/工具类 |

## 常用命令

```bash
# 启动中间件
docker compose up -d

# 单模块重载
./scripts/reload.sh order

# 全量重载
./scripts/reload.sh all

# Maven 编译(跳过测试)
mvn clean package -DskipTests
```

## 关键设计

### 消息队列使用

- **RocketMQ 延迟消息**: 订单创建后发送 30 分钟延迟消息到 `order-timeout-topic`，超时自动关单
- **RocketMQ 普通消息**: 支付结果通过 `pay-result-topic` 异步通知订单服务
- **Kafka**: 支付操作日志投递到 `pay-log-topic`，notification 服务消费

### 订单状态机

```
0(待支付) → 4(支付中) → 1(已支付) → 6(退款中) → 3(已退款)
                     → 5(支付失败)
         → 2(已关闭/超时)
```

### 幂等策略

1. Redis 分布式锁 `pay_lock:{orderId}` 防止并发支付
2. 数据库唯一索引 `t_idempotent_record(biz_type, biz_key)` 防止重复回调
3. 状态机校验防止无效状态流转

### 回调签名验证

- 算法: HmacSHA256
- 密钥: `MiniPayCallbackSecret2026`
- 参数按 TreeMap 排序后拼接签名

## 目录结构约定

- Controller: `*/controller/*.java`
- Service: `*/service/*.java`
- MQ 监听: `*/mq/*.java`, `*/listener/*.java`
- Feign 客户端: `*/feign/*.java`
- Mapper: `*/mapper/*.java`
- 配置: `*/resources/application.yml`
- 数据库初始化: `sql/init.sql`

## 日志与调试

- 日志: `logs/*.log`
- PID: `.run/*.pid`
- 链路追踪: 每个操作记录到 `t_order_trace` 表
