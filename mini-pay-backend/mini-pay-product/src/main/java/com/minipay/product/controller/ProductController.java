package com.minipay.product.controller;

import com.minipay.common.entity.Product;
import com.minipay.common.result.R;
import com.minipay.product.service.ProductService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/product")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/list")
    public R<List<Product>> list() {
        return R.ok(productService.listProducts());
    }

    @GetMapping("/{id}")
    public R<Product> detail(@PathVariable Long id) {
        return R.ok(productService.getById(id));
    }

    /**
     * 内部接口：扣减库存（被订单服务通过 Feign 调用）
     */
    @PostMapping("/deduct-stock")
    public R<Boolean> deductStock(@RequestParam Long productId, @RequestParam Integer quantity) {
        boolean success = productService.deductStock(productId, quantity);
        return success ? R.ok(true) : R.fail("库存不足");
    }

    /**
     * 内部接口：恢复库存
     */
    @PostMapping("/restore-stock")
    public R<Void> restoreStock(@RequestParam Long productId, @RequestParam Integer quantity) {
        productService.restoreStock(productId, quantity);
        return R.ok();
    }
}
