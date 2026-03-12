-- Mini Pay 数据库初始化脚本
CREATE DATABASE IF NOT EXISTS mini_pay DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE mini_pay;

-- 用户表
CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密密码',
    phone VARCHAR(20) DEFAULT NULL COMMENT '手机号',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username)
) ENGINE=InnoDB COMMENT='用户表';

-- 商品表
CREATE TABLE IF NOT EXISTS t_product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL COMMENT '商品名称',
    description VARCHAR(500) DEFAULT '' COMMENT '商品描述',
    price DECIMAL(10,2) NOT NULL COMMENT '价格',
    stock INT NOT NULL DEFAULT 0 COMMENT '库存',
    image VARCHAR(200) DEFAULT '' COMMENT '图片',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB COMMENT='商品表';

-- 订单表
-- 面试考点：id 使用 BIGINT 存储雪花算法生成的分布式ID
CREATE TABLE IF NOT EXISTS t_order (
    id BIGINT PRIMARY KEY COMMENT '雪花算法ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    product_id BIGINT NOT NULL COMMENT '商品ID',
    product_name VARCHAR(100) NOT NULL COMMENT '商品名称（冗余，避免跨服务查询）',
    quantity INT NOT NULL DEFAULT 1 COMMENT '数量',
    amount DECIMAL(10,2) NOT NULL COMMENT '订单金额',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-待支付 1-已支付 2-已关闭 3-已退款 4-支付中 5-支付失败 6-退款中',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    pay_time DATETIME DEFAULT NULL COMMENT '支付时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB COMMENT='订单表';

-- 支付记录表
CREATE TABLE IF NOT EXISTS t_pay_record (
    id BIGINT PRIMARY KEY COMMENT '雪花算法ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    amount DECIMAL(10,2) NOT NULL COMMENT '支付金额',
    channel VARCHAR(20) NOT NULL COMMENT '支付渠道: alipay, wechat, bank',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-处理中 1-成功 2-失败',
    out_trade_no VARCHAR(64) DEFAULT NULL COMMENT '商户侧支付单号',
    channel_txn_no VARCHAR(64) DEFAULT NULL COMMENT '渠道侧交易流水号',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    finish_time DATETIME DEFAULT NULL COMMENT '完成时间',
    callback_time DATETIME DEFAULT NULL COMMENT '回调时间',
    callback_raw TEXT DEFAULT NULL COMMENT '回调原始报文',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_order_id (order_id),
    INDEX idx_user_id (user_id),
    UNIQUE KEY uk_out_trade_no (out_trade_no),
    UNIQUE KEY uk_channel_txn_no (channel_txn_no)
) ENGINE=InnoDB COMMENT='支付记录表';

-- 退款记录表
CREATE TABLE IF NOT EXISTS t_refund_record (
    id BIGINT PRIMARY KEY COMMENT '雪花算法ID',
    order_id BIGINT NOT NULL COMMENT '订单ID',
    pay_id BIGINT DEFAULT NULL COMMENT '支付记录ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    refund_no VARCHAR(64) NOT NULL COMMENT '商户侧退款单号',
    channel_refund_no VARCHAR(64) DEFAULT NULL COMMENT '渠道退款流水号',
    amount DECIMAL(10,2) NOT NULL COMMENT '退款金额',
    reason VARCHAR(255) DEFAULT '' COMMENT '退款原因',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-处理中 1-成功 2-失败',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    finish_time DATETIME DEFAULT NULL COMMENT '完成时间',
    callback_time DATETIME DEFAULT NULL COMMENT '回调时间',
    callback_raw TEXT DEFAULT NULL COMMENT '回调原始报文',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_refund_no (refund_no),
    UNIQUE KEY uk_channel_refund_no (channel_refund_no),
    INDEX idx_order_id (order_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB COMMENT='退款记录表';

-- 幂等记录表
CREATE TABLE IF NOT EXISTS t_idempotent_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    biz_type VARCHAR(64) NOT NULL COMMENT '业务类型: PAY_CALLBACK / REFUND_CALLBACK / ...',
    biz_key VARCHAR(128) NOT NULL COMMENT '业务唯一键',
    request_id VARCHAR(128) DEFAULT NULL COMMENT '请求唯一ID',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-处理中 1-成功 2-失败',
    payload TEXT DEFAULT NULL COMMENT '扩展数据',
    expire_time DATETIME DEFAULT NULL COMMENT '幂等失效时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_biz_type_key (biz_type, biz_key),
    INDEX idx_request_id (request_id)
) ENGINE=InnoDB COMMENT='幂等记录表';

-- 兼容历史库：增量补字段（MySQL 8.0+）
ALTER TABLE t_pay_record ADD COLUMN IF NOT EXISTS out_trade_no VARCHAR(64) DEFAULT NULL COMMENT '商户侧支付单号';
ALTER TABLE t_pay_record ADD COLUMN IF NOT EXISTS channel_txn_no VARCHAR(64) DEFAULT NULL COMMENT '渠道侧交易流水号';
ALTER TABLE t_pay_record ADD COLUMN IF NOT EXISTS callback_time DATETIME DEFAULT NULL COMMENT '回调时间';
ALTER TABLE t_pay_record ADD COLUMN IF NOT EXISTS callback_raw TEXT DEFAULT NULL COMMENT '回调原始报文';
ALTER TABLE t_pay_record ADD COLUMN IF NOT EXISTS update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

-- 订单调用链步骤表（用于前端按订单查看完整链路）
CREATE TABLE IF NOT EXISTS t_order_trace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL COMMENT '订单ID',
    step_no INT NOT NULL DEFAULT 0 COMMENT '步骤顺序（同一次链路内）',
    service VARCHAR(100) NOT NULL COMMENT '服务名',
    action VARCHAR(200) NOT NULL COMMENT '动作描述',
    tech VARCHAR(100) NOT NULL COMMENT '技术点',
    detail VARCHAR(1000) NOT NULL COMMENT '详细说明',
    status VARCHAR(20) NOT NULL COMMENT 'success / warn / fail',
    step_timestamp BIGINT NOT NULL COMMENT '步骤时间戳(ms)',
    duration BIGINT NOT NULL DEFAULT 0 COMMENT '耗时(ms)',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_order_id_time (order_id, step_timestamp, id)
) ENGINE=InnoDB COMMENT='订单调用链步骤表';

-- 初始商品数据
INSERT INTO t_product (name, description, price, stock, image) VALUES
('VIP 月卡', '享受一个月 VIP 特权', 30.00, 999, '💎'),
('VIP 季卡', '享受三个月 VIP 特权，更划算', 80.00, 500, '👑'),
('VIP 年卡', '全年 VIP，尊享最大优惠', 268.00, 200, '🏆'),
('积分充值 100', '充值 100 积分', 10.00, 9999, '🪙'),
('积分充值 500', '充值 500 积分，赠送 50', 50.00, 9999, '💰'),
('定制服务', '一对一定制开发服务', 999.00, 10, '🛠️');
