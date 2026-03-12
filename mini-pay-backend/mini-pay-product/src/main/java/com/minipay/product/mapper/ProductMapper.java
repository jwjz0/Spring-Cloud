package com.minipay.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.minipay.common.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 扣减库存（乐观锁方式，防超卖）
     *
     * 面试考点：
     * WHERE stock >= #{quantity} 是乐观锁思想
     * 如果并发扣减导致库存不足，update 影响行数为 0，业务层判断失败
     */
    @Update("UPDATE t_product SET stock = stock - #{quantity} WHERE id = #{id} AND stock >= #{quantity}")
    int deductStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 恢复库存（退款/取消时使用）
     */
    @Update("UPDATE t_product SET stock = stock + #{quantity} WHERE id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("quantity") Integer quantity);
}
