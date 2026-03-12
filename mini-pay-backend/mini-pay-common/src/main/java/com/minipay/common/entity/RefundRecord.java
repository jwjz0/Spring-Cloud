package com.minipay.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.minipay.common.constant.RefundStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退款记录
 */
@TableName("t_refund_record")
public class RefundRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orderId;
    private Long payId;
    private Long userId;
    private String refundNo;
    private String channelRefundNo;
    private BigDecimal amount;
    private String reason;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
    private LocalDateTime callbackTime;
    private String callbackRaw;
    private LocalDateTime updateTime;

    public static final int STATUS_PROCESSING = RefundStatus.PROCESSING;
    public static final int STATUS_SUCCESS = RefundStatus.SUCCESS;
    public static final int STATUS_FAIL = RefundStatus.FAIL;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getPayId() {
        return payId;
    }

    public void setPayId(Long payId) {
        this.payId = payId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRefundNo() {
        return refundNo;
    }

    public void setRefundNo(String refundNo) {
        this.refundNo = refundNo;
    }

    public String getChannelRefundNo() {
        return channelRefundNo;
    }

    public void setChannelRefundNo(String channelRefundNo) {
        this.channelRefundNo = channelRefundNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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

    public LocalDateTime getFinishTime() {
        return finishTime;
    }

    public void setFinishTime(LocalDateTime finishTime) {
        this.finishTime = finishTime;
    }

    public LocalDateTime getCallbackTime() {
        return callbackTime;
    }

    public void setCallbackTime(LocalDateTime callbackTime) {
        this.callbackTime = callbackTime;
    }

    public String getCallbackRaw() {
        return callbackRaw;
    }

    public void setCallbackRaw(String callbackRaw) {
        this.callbackRaw = callbackRaw;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}

