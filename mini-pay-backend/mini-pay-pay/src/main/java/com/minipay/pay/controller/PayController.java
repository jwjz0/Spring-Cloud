package com.minipay.pay.controller;

import com.minipay.common.dto.PayCallbackRequest;
import com.minipay.common.dto.RefundCallbackRequest;
import com.minipay.common.dto.RefundRequest;
import com.minipay.common.dto.TraceResult;
import com.minipay.common.entity.PayRecord;
import com.minipay.common.entity.RefundRecord;
import com.minipay.common.result.R;
import com.minipay.pay.service.PayService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/pay")
public class PayController {

    private final PayService payService;

    public PayController(PayService payService) {
        this.payService = payService;
    }

    @PostMapping("/create")
    public R<TraceResult<PayRecord>> create(@RequestBody Map<String, Object> params,
                               @RequestHeader("X-User-Id") Long userId) {
        Long orderId = Long.parseLong(params.get("orderId").toString());
        String channel = (String) params.getOrDefault("channel", "alipay");
        TraceResult<PayRecord> result = payService.createPay(userId, orderId, channel);
        return R.ok(result);
    }

    @GetMapping("/query/{payId}")
    public R<PayRecord> query(@PathVariable Long payId) {
        return R.ok(payService.queryPay(payId));
    }

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

    /**
     * 按订单查最新支付记录（前端模拟回调时需要获取 outTradeNo）
     */
    @GetMapping("/query-by-order/{orderId}")
    public R<PayRecord> queryByOrder(@PathVariable Long orderId) {
        return R.ok(payService.queryByOrderId(orderId));
    }
}
