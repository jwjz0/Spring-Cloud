package com.minipay.common.dto;

import java.math.BigDecimal;

/**
 * 模拟支付渠道回调请求
 */
public class PayCallbackRequest {

    private String outTradeNo;
    private String channelTxnNo;
    private String payStatus;
    private String channel;
    private BigDecimal paidAmount;
    private String failReason;
    private Long callbackTimestamp;
    private String nonce;
    private String rawBody;
    private String sign;

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

    public String getPayStatus() {
        return payStatus;
    }

    public void setPayStatus(String payStatus) {
        this.payStatus = payStatus;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public Long getCallbackTimestamp() {
        return callbackTimestamp;
    }

    public void setCallbackTimestamp(Long callbackTimestamp) {
        this.callbackTimestamp = callbackTimestamp;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public String getRawBody() {
        return rawBody;
    }

    public void setRawBody(String rawBody) {
        this.rawBody = rawBody;
    }

    public String getSign() {
        return sign;
    }

    public void setSign(String sign) {
        this.sign = sign;
    }
}

