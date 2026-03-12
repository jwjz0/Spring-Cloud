package com.minipay.pay.feign;

import com.minipay.common.entity.Order;
import com.minipay.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "order-service")
public interface OrderFeignClient {

    @GetMapping("/api/order/{id}")
    R<Order> getOrder(@PathVariable("id") Long id);

    @PostMapping("/api/order/mark-paid")
    R<Void> markPaid(@RequestParam("orderId") Long orderId,
                     @RequestParam(value = "channelTxnNo", required = false) String channelTxnNo);

    @PostMapping("/api/order/mark-paying")
    R<Void> markPaying(@RequestParam("orderId") Long orderId);

    @PostMapping("/api/order/mark-pay-failed")
    R<Void> markPayFailed(@RequestParam("orderId") Long orderId,
                          @RequestParam(value = "reason", required = false) String reason);

    @PostMapping("/api/order/mark-refunding")
    R<Void> markRefunding(@RequestParam("orderId") Long orderId,
                          @RequestParam(value = "reason", required = false) String reason);

    @PostMapping("/api/order/mark-refunded")
    R<Void> markRefunded(@RequestParam("orderId") Long orderId,
                         @RequestParam(value = "refundNo", required = false) String refundNo);
}
