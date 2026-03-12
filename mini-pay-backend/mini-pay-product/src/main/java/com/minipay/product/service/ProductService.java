package com.minipay.product.service;

import com.minipay.common.entity.Product;
import com.minipay.product.mapper.ProductMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 商品服务
 *
 * 面试考点 - Redis 缓存策略：
 * 1. 缓存穿透：查询不存在的数据 → 缓存空值
 * 2. 缓存击穿：热点 key 过期，大量请求打到 DB → 互斥锁
 * 3. 缓存雪崩：大量 key 同时过期 → 随机过期时间
 */
@Service
public class ProductService {

    private final ProductMapper productMapper;
    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_KEY = "product:list";
    private static final long CACHE_EXPIRE_MINUTES = 10;

    public ProductService(ProductMapper productMapper, StringRedisTemplate redisTemplate) {
        this.productMapper = productMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 获取商品列表（带 Redis 缓存）
     */
    public List<Product> listProducts() {
        // TODO: 后续可加入 Redis 缓存逻辑
        // 目前直接查数据库
        return productMapper.selectList(null);
    }

    public Product getById(Long id) {
        return productMapper.selectById(id);
    }

    /**
     * 扣减库存
     * @return true 扣减成功, false 库存不足
     */
    public boolean deductStock(Long productId, Integer quantity) {
        int rows = productMapper.deductStock(productId, quantity);
        return rows > 0;
    }

    /**
     * 恢复库存
     */
    public void restoreStock(Long productId, Integer quantity) {
        productMapper.restoreStock(productId, quantity);
    }
}
