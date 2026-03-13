# Mini Pay Web - CLAUDE.md

## 项目概述

Vue 3 + TypeScript 前端，配合 mini-pay-backend 微服务后端，提供支付系统完整交互界面。

## 技术栈

- Vue 3.5 + TypeScript 5.9
- Vite 7.3 (构建工具)
- Pinia 3.0 (状态管理)
- Vue Router 4.6 (路由, Hash 模式)
- Element Plus 2.13 (UI 组件库)
- Axios 1.13 (HTTP 客户端)
- CryptoJS 4.2 (HmacSHA256 签名)

## 常用命令

```bash
npm install     # 安装依赖
npm run dev     # 启动开发服务器
npm run build   # 生产构建
```

## 目录结构

```
src/
├── api/
│   ├── index.ts          # 所有 API 方法 (user/product/order/pay/admin)
│   └── request.ts        # Axios 实例, 拦截器, Bearer Token
├── components/
│   ├── Layout.vue        # 主布局 (侧边栏 + 内容区)
│   └── TraceTimeline.vue # 分布式链路时间线组件
├── router/index.ts       # 路由定义 + 登录守卫
├── stores/user.ts        # Pinia 用户状态 (token/username)
├── utils/sign.ts         # HmacSHA256 签名工具
└── views/
    ├── login/LoginView.vue       # 登录/注册
    ├── product/ProductList.vue   # 商品列表 + 压测工具
    ├── order/OrderList.vue       # 订单管理 + 退款 + 模拟回调
    ├── pay/PayView.vue           # 支付流程 (创建→等待回调→结果)
    └── admin/AdminDashboard.vue  # 管理后台统计
```

## 路由

| 路径 | 组件 | 说明 |
|------|------|------|
| /login | LoginView | 无需鉴权 |
| /product | ProductList | 商品列表 |
| /test-tools | ProductList | 压测工具(复用) |
| /order | OrderList | 我的订单 |
| /pay/:orderId | PayView | 支付页 |
| /admin | AdminDashboard | 管理后台 |

## 关键设计

### 认证流程

1. 登录获取 JWT → 存 localStorage
2. Axios 拦截器自动附加 `Bearer token`
3. 401 响应自动登出并跳转 /login

### API 基础配置

- Base URL: `http://localhost:8080/api` (通过 Gateway)
- 超时: 10 秒

### 模拟回调机制

前端可模拟支付/退款回调，生成 HmacSHA256 签名后 POST 到后端:
- 密钥: `MiniPayCallbackSecret2026`
- 参数按字母序排序后拼接签名

### 压测功能

ProductList 页面支持并发/串行压测，统计 QPS、延迟、成功率。
