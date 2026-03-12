package com.minipay.order.controller;

import com.minipay.common.dto.CreateOrderRequest;
import com.minipay.common.dto.TraceResult;
import com.minipay.common.dto.TraceStep;
import com.minipay.common.entity.Order;
import com.minipay.common.result.R;
import com.minipay.order.service.OrderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/create")
    public R<TraceResult<Order>> create(@RequestBody CreateOrderRequest request,
                           @RequestHeader("X-User-Id") Long userId) {
        TraceResult<Order> result = orderService.createOrder(userId, request);
        return R.ok(result);
    }

    @GetMapping("/list")
    public R<List<Order>> list(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(orderService.listByUserId(userId));
    }

    @GetMapping("/{id}")
    public R<Order> detail(@PathVariable Long id) {
        return R.ok(orderService.getById(id));
    }

    @GetMapping("/trace/{id}")
    public R<List<TraceStep>> trace(@PathVariable("id") Long orderId,
                                    @RequestHeader("X-User-Id") Long userId) {
        return R.ok(orderService.getTraceByOrderId(userId, orderId));
    }

    /**
     * 内部接口：标记订单已支付（被支付服务通过 Feign 调用）
     * 面试考点：MQ 不可用时的降级方案 —— 同步 Feign 调用兜底
     */
    @PostMapping("/mark-paid")
    public R<Void> markPaid(@RequestParam Long orderId,
                            @RequestParam(required = false) String channelTxnNo) {
        orderService.markAsPaidByFeignFallback(orderId, channelTxnNo);
        return R.ok();
    }

    /**
     * 内部接口：标记订单为支付中
     */
    @PostMapping("/mark-paying")
    public R<Void> markPaying(@RequestParam Long orderId) {
        orderService.markAsPaying(orderId);
        return R.ok();
    }

    /**
     * 内部接口：标记订单支付失败
     */
    @PostMapping("/mark-pay-failed")
    public R<Void> markPayFailed(@RequestParam Long orderId,
                                 @RequestParam(required = false) String reason) {
        orderService.markAsPayFailed(orderId, reason);
        return R.ok();
    }

    /**
     * 内部接口：标记订单退款中
     */
    @PostMapping("/mark-refunding")
    public R<Void> markRefunding(@RequestParam Long orderId,
                                 @RequestParam(required = false) String reason) {
        orderService.markAsRefunding(orderId, reason);
        return R.ok();
    }

    /**
     * 内部接口：标记订单已退款
     */
    @PostMapping("/mark-refunded")
    public R<Void> markRefunded(@RequestParam Long orderId,
                                @RequestParam(required = false) String refundNo) {
        orderService.markAsRefunded(orderId, refundNo);
        return R.ok();
    }
}
