package com.minipay.order.controller;

import com.minipay.common.result.R;
import com.minipay.order.service.OrderService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final OrderService orderService;

    public AdminController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/dashboard")
    public R<Map<String, Object>> dashboard() {
        return R.ok(orderService.getDashboardStats());
    }

    @PostMapping("/reset-test-data")
    public R<Map<String, Object>> resetTestData() {
        return R.ok(orderService.resetTestData());
    }
}
