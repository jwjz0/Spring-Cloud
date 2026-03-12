package com.minipay.order.feign;

import com.minipay.common.entity.Product;
import com.minipay.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 商品服务 Feign 客户端
 *
 * 面试考点：
 * 1. @FeignClient("product-service") 指定要调用的服务名（注册在 Nacos 中的名称）
 * 2. Feign 底层使用 JDK 动态代理，将接口方法转为 HTTP 请求
 * 3. 结合 LoadBalancer 实现客户端负载均衡（轮询、随机等策略）
 * 4. Feign 支持熔断降级：@FeignClient(fallback = XxxFallback.class)
 */
@FeignClient(name = "product-service")
public interface ProductFeignClient {

    @GetMapping("/api/product/{id}")
    R<Product> getProduct(@PathVariable("id") Long id);

    @PostMapping("/api/product/deduct-stock")
    R<Boolean> deductStock(@RequestParam("productId") Long productId,
                           @RequestParam("quantity") Integer quantity);

    @PostMapping("/api/product/restore-stock")
    R<Void> restoreStock(@RequestParam("productId") Long productId,
                         @RequestParam("quantity") Integer quantity);
}
