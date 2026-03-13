# Mini Pay - 微服务支付系统

> 一个用于学习微服务架构与消息队列的全栈支付系统项目，旨在通过实战掌握 Spring Cloud 微服务拆分、RocketMQ/Kafka 消息驱动、分布式事务与幂等控制等核心技能，为暑期支付公司实习做准备。

## 项目初衷

在支付领域，**微服务拆分**和**消息队列**是两大核心基础设施。本项目从零构建一个完整的支付链路，覆盖：

- **微服务架构实践** — 网关鉴权、服务注册发现、Feign 远程调用、负载均衡
- **消息队列核心场景** — RocketMQ 延迟消息(超时关单)、异步通知(支付结果)；Kafka 日志流(审计追踪)
- **支付领域关键问题** — 幂等控制、分布式锁、状态机流转、回调签名验证、退款流程

## 系统架构

```
┌─────────────┐     ┌──────────────────────────────────────────────┐
│  Vue 3 前端  │────▶│  Spring Cloud Gateway (8080)                 │
│  (Vite)     │     │  JWT 鉴权 · 路由转发 · CORS                   │
└─────────────┘     └──────┬──────────┬──────────┬─────────────────┘
                           │          │          │
                    ┌──────▼───┐ ┌────▼─────┐ ┌─▼──────────┐
                    │ User     │ │ Product  │ │ Order      │
                    │ (8081)   │ │ (8082)   │ │ (8083)     │
                    │ 注册/登录 │ │ 商品/库存 │ │ 下单/状态机 │
                    └──────────┘ └──────────┘ └──┬─────────┘
                                                  │ Feign / RocketMQ
                                            ┌─────▼──────┐
                                            │ Pay (8084) │
                                            │ 支付/退款   │
                                            │ Redis 幂等锁│
                                            └──┬─────────┘
                                               │ Kafka
                                        ┌──────▼────────────┐
                                        │ Notification(8085)│
                                        │ 日志消费/审计扩展   │
                                        └───────────────────┘

  中间件: Nacos (注册中心) · MySQL · Redis · RocketMQ · Kafka
```

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 21 | 运行时 |
| Spring Boot | 3.4.3 | 基础框架 |
| Spring Cloud | 2024.0.1 | 微服务治理 (Gateway / Feign / LoadBalancer) |
| Spring Cloud Alibaba | 2023.0.3.2 | Nacos 服务注册与发现 |
| MyBatis Plus | 3.5.9 | ORM 持久层 |
| RocketMQ | - | 延迟关单 · 支付结果异步通知 |
| Kafka | KRaft 模式 | 支付日志流 |
| Redis | - | 分布式锁 · 缓存 |
| MySQL | 8.0 | 业务数据库 |
| JWT (jjwt) | 0.12.6 | 用户认证 |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| Vue | 3.5 | UI 框架 |
| TypeScript | 5.9 | 类型安全 |
| Vite | 7.3 | 构建工具 |
| Element Plus | 2.13 | UI 组件库 |
| Pinia | 3.0 | 状态管理 |
| Axios | 1.13 | HTTP 请求 |

## 核心学习点

### 1. 消息队列应用场景

| 场景 | 中间件 | 实现方式 |
|------|--------|----------|
| 订单超时自动关单 | RocketMQ | 延迟消息 (30 分钟延迟级别) |
| 支付结果异步通知 | RocketMQ | 普通消息，订单服务消费后更新状态 |
| 支付操作审计日志 | Kafka | 日志流投递，notification 服务消费 |

### 2. 分布式事务与幂等

- **Redis 分布式锁**: 防止同一订单并发支付
- **数据库唯一索引**: `t_idempotent_record(biz_type, biz_key)` 防止重复回调处理
- **状态机校验**: 每次状态流转前检查当前状态合法性

### 3. 支付回调签名验证

- 使用 HmacSHA256 对回调参数签名
- 参数按字母序排序后拼接，防篡改

### 4. 订单状态机

```
待支付(0) ──▶ 支付中(4) ──▶ 已支付(1) ──▶ 退款中(6) ──▶ 已退款(3)
    │                   └──▶ 支付失败(5)
    └──▶ 已关闭(2, 超时)
```

## 快速启动

### 环境要求

- JDK 21+
- Node.js 18+
- Docker & Docker Compose
- Maven 3.9+

### 1. 启动中间件

```bash
cd mini-pay-backend
docker compose up -d    # 启动 Nacos、RocketMQ、Kafka
```

### 2. 初始化数据库

```bash
# 导入 SQL 到 MySQL
mysql -u root -p416416 < mini-pay-backend/sql/init.sql
```

### 3. 启动后端服务

```bash
cd mini-pay-backend

# 全量编译并启动所有服务
./scripts/reload.sh all

# 或逐个启动
./scripts/reload.sh gateway user product order pay notification
```

### 4. 启动前端

```bash
cd mini-pay-web
npm install
npm run dev
```

访问 http://localhost:5173 即可使用。

## 项目结构

```
.
├── mini-pay-backend/           # 后端微服务
│   ├── mini-pay-gateway/       # API 网关
│   ├── mini-pay-user/          # 用户服务
│   ├── mini-pay-product/       # 商品服务
│   ├── mini-pay-order/         # 订单服务
│   ├── mini-pay-pay/           # 支付服务
│   ├── mini-pay-notification/  # 通知服务
│   ├── mini-pay-common/        # 公共模块
│   ├── sql/                    # 数据库初始化脚本
│   ├── scripts/                # 运维脚本
│   └── docker-compose.yml      # 中间件容器编排
├── mini-pay-web/               # 前端 Vue 3 应用
│   └── src/
│       ├── api/                # API 层
│       ├── views/              # 页面
│       ├── components/         # 组件
│       ├── stores/             # 状态管理
│       └── router/             # 路由
└── docs/                       # 文档
```

## License

MIT
