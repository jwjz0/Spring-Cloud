package com.minipay.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.minipay.common.constant.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体
 *
 * 面试考点 - 订单状态机：
 * PENDING(待支付) → PAID(已支付) → COMPLETED(已完成)
 *                → CLOSED(超时关闭)
 * PAID → REFUNDED(已退款)
 */
@TableName("t_order")
public class Order {
    @TableId(type = IdType.ASSIGN_ID) // 雪花算法生成分布式ID
    private Long id;
    private Long userId;
    private Long productId;
    private String productName;
    private Integer quantity;
    private BigDecimal amount;
    /**
     * 订单状态（兼容旧值）:
     * 0-待支付 1-已支付 2-已关闭 3-已退款 4-支付中 5-支付失败 6-退款中
     */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime updateTime;

    // 状态常量
    public static final int STATUS_PENDING = OrderStatus.PENDING;
    public static final int STATUS_PAID = OrderStatus.PAID;
    public static final int STATUS_CLOSED = OrderStatus.CLOSED;
    public static final int STATUS_REFUNDED = OrderStatus.REFUNDED;
    public static final int STATUS_PAYING = OrderStatus.PAYING;
    public static final int STATUS_PAY_FAILED = OrderStatus.PAY_FAILED;
    public static final int STATUS_REFUNDING = OrderStatus.REFUNDING;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getPayTime() {
        return payTime;
    }

    public void setPayTime(LocalDateTime payTime) {
        this.payTime = payTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    @Override
    public String toString() {
        return "Order{" +
                "id=" + id +
                ", userId=" + userId +
                ", productId=" + productId +
                ", productName='" + productName + '\'' +
                ", quantity=" + quantity +
                ", amount=" + amount +
                ", status=" + status +
                ", createTime=" + createTime +
                ", payTime=" + payTime +
                ", updateTime=" + updateTime +
                '}';
    }
}
