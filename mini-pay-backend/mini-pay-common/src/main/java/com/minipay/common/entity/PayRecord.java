package com.minipay.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.minipay.common.constant.PayStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("t_pay_record")
public class PayRecord {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long orderId;
    private Long userId;
    private BigDecimal amount;
    private String channel; // alipay, wechat, bank
    /**
     * 支付状态: 0-处理中 1-成功 2-失败
     */
    private Integer status;
    /**
     * 商户侧支付单号
     */
    private String outTradeNo;
    /**
     * 渠道侧交易流水号
     */
    private String channelTxnNo;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
    private LocalDateTime callbackTime;
    private String callbackRaw;
    private LocalDateTime updateTime;

    public static final int STATUS_PROCESSING = PayStatus.PROCESSING;
    public static final int STATUS_SUCCESS = PayStatus.SUCCESS;
    public static final int STATUS_FAIL = PayStatus.FAIL;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
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

    public String getOutTradeNo() {
        return outTradeNo;
    }

    public void setOutTradeNo(String outTradeNo) {
        this.outTradeNo = outTradeNo;
    }

    public String getChannelTxnNo() {
        return channelTxnNo;
    }

    public void setChannelTxnNo(String channelTxnNo) {
        this.channelTxnNo = channelTxnNo;
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

    @Override
    public String toString() {
        return "PayRecord{" +
                "id=" + id +
                ", orderId=" + orderId +
                ", userId=" + userId +
                ", amount=" + amount +
                ", channel='" + channel + '\'' +
                ", status=" + status +
                ", outTradeNo='" + outTradeNo + '\'' +
                ", channelTxnNo='" + channelTxnNo + '\'' +
                ", createTime=" + createTime +
                ", finishTime=" + finishTime +
                ", callbackTime=" + callbackTime +
                ", updateTime=" + updateTime +
                '}';
    }
}
