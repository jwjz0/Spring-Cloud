package com.minipay.common.constant;

/**
 * 退款状态常量
 */
public final class RefundStatus {

    private RefundStatus() {
    }

    public static final int PROCESSING = 0; // 处理中
    public static final int SUCCESS = 1;    // 成功
    public static final int FAIL = 2;       // 失败
}

