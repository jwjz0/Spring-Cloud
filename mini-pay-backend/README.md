# Mini Pay Backend

本目录是 `mini-pay` 的后端微服务工程（Spring Cloud + Nacos + MySQL + Redis + Kafka + RocketMQ）。

## 模块说明

- `mini-pay-gateway`（`8080`）
  - API 网关，统一入口
  - 路由转发到 user/product/order/pay
  - 统一鉴权（JWT）与过滤器
- `mini-pay-user`（`8081`）
  - 用户注册、登录、用户信息
- `mini-pay-product`（`8082`）
  - 商品查询、库存扣减/回滚
- `mini-pay-order`（`8083`）
  - 下单、订单查询、订单链路
  - RocketMQ 延迟消息（超时关单）
- `mini-pay-pay`（`8084`）
  - 支付、幂等控制（Redis 锁）
  - 支付结果通知（RocketMQ）
  - 支付日志投递（Kafka）
- `mini-pay-notification`（`8085`）
  - 消费 Kafka 支付日志，用于通知/审计扩展
- `mini-pay-common`
  - 公共 DTO / Entity / 工具类 / 通用配置

## 一键重载脚本（改完即重载）

已提供脚本：`scripts/reload.sh`

功能：
- 自动编译目标模块（`mvn -pl <module> -am -DskipTests package`）
- 自动停止旧进程（按 PID 文件 + 端口兜底）
- 自动启动新 jar，并等待端口就绪
- 自动校验 Nacos 注册（`127.0.0.1 + 对应端口`）
- 输出启动结果，失败时回显最后日志

### 先给脚本执行权限

```bash
cd /Users/jizhi/Documents/Yeepay/mini-pay-backend
chmod +x scripts/reload.sh
```

### 用法

```bash
# 单模块重载（推荐：改完哪个重载哪个）
./scripts/reload.sh gateway
./scripts/reload.sh user
./scripts/reload.sh product
./scripts/reload.sh order
./scripts/reload.sh pay
./scripts/reload.sh notification

# 多模块重载
./scripts/reload.sh order pay

# 全量重载（会先全量编译，再逐个重启）
./scripts/reload.sh all
```

## 推荐开发流程

1. 启动中间件（Nacos / RocketMQ / Kafka）
```bash
cd /Users/jizhi/Documents/Yeepay/mini-pay-backend
docker compose up -d
```

2. 修改后端代码后，立即重载对应模块
```bash
./scripts/reload.sh <module>
```

3. 如果改了 `mini-pay-common`，建议全量重载
```bash
./scripts/reload.sh all
```

## 日志与运行文件

- 日志目录：`logs/`
  - 例如：`logs/mini-pay-order.log`
- PID 文件目录：`.run/`
  - 例如：`.run/mini-pay-order.pid`

## 常见问题

- 出现 `No servers available for service` 或网关 `Host is down`
  - 通常是服务未启动或注册信息失效
  - 直接执行：
  ```bash
  ./scripts/reload.sh all
  ```
- 当前配置已固定服务注册 IP 为 `127.0.0.1`，避免多网卡/VPN 场景下 Nacos 选错 IP。

- 修改代码后接口还是旧逻辑
  - 说明模块未重载成功，检查对应 `logs/*.log`。
