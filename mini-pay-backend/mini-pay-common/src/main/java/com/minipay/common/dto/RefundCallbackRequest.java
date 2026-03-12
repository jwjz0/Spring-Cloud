package com.minipay.common.dto;

import java.math.BigDecimal;

/**
 * 模拟退款回调请求
 */
public class RefundCallbackRequest {

    private String refundNo;
    private String channelRefundNo;
    private String refundStatus;
    private BigDecimal refundAmount;
    private String failReason;
    private Long callbackTimestamp;
    private String nonce;
    private String rawBody;
    private String sign;

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

    public String getRefundStatus() {
        return refundStatus;
    }

    public void setRefundStatus(String refundStatus) {
        this.refundStatus = refundStatus;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
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

