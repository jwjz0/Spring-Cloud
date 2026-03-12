package com.minipay.common.constant;

/**
 * 订单状态常量（兼容当前历史数据）
 *
 * 说明：
 * - 0~3 为历史状态编码，保持不变，避免影响现有数据与逻辑。
 * - 新增状态从 4 开始扩展，用于支付回调闭环与退款状态机。
 */
public final class OrderStatus {

    private OrderStatus() {
    }

    public static final int PENDING = 0;      // 待支付
    public static final int PAID = 1;         // 已支付（历史值）
    public static final int CLOSED = 2;       // 已关闭
    public static final int REFUNDED = 3;     // 已退款
    public static final int PAYING = 4;       // 支付中（新增）
    public static final int PAY_FAILED = 5;   // 支付失败（新增）
    public static final int REFUNDING = 6;    // 退款中（新增）
}

