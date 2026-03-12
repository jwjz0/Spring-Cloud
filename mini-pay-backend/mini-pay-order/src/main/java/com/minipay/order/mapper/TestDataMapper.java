package com.minipay.order.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TestDataMapper {

    @Delete("DELETE FROM t_order_trace")
    int deleteAllOrderTrace();

    @Delete("DELETE FROM t_pay_record")
    int deleteAllPayRecord();

    @Delete("DELETE FROM t_order")
    int deleteAllOrder();

    @Delete("DELETE FROM t_refund_record")
    int deleteAllRefundRecord();

    @Delete("DELETE FROM t_idempotent_record")
    int deleteAllIdempotentRecord();

    @Update("UPDATE t_product SET stock = CASE id " +
            "WHEN 1 THEN 999 " +
            "WHEN 2 THEN 500 " +
            "WHEN 3 THEN 200 " +
            "WHEN 4 THEN 9999 " +
            "WHEN 5 THEN 9999 " +
            "WHEN 6 THEN 10 " +
            "ELSE stock END " +
            "WHERE id IN (1,2,3,4,5,6)")
    int resetProductStockToInit();
}
